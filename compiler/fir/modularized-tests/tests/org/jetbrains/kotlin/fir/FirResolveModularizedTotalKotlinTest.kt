/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.management.HotSpotDiagnosticMXBean
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.cli.common.profiling.AsyncProfilerHelper
import org.jetbrains.kotlin.cli.common.toBooleanLenient
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.fir.analysis.FirCheckersResolveProcessor
import org.jetbrains.kotlin.fir.builder.RawFirBuilder
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.dump.MultiModuleHtmlFirDump
import org.jetbrains.kotlin.fir.lightTree.LightTree2Fir
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirProviderImpl
import org.jetbrains.kotlin.fir.resolve.transformers.createAllCompilerResolveProcessors
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.perfstat.PerfStat
import org.jetbrains.kotlin.perfstat.StatResult
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import kotlin.reflect.KClass


private const val FAIL_FAST = true

private const val FIR_DUMP_PATH = "tmp/firDump"
private const val FIR_HTML_DUMP_PATH = "tmp/firDump-html"
const val FIR_LOGS_PATH = "tmp/fir-logs"
private const val FIR_MEMORY_DUMPS_PATH = "tmp/memory-dumps"

private val DUMP_FIR = System.getProperty("fir.bench.dump", "true").toBooleanLenient()!!
internal val PASSES = System.getProperty("fir.bench.passes")?.toInt() ?: 3
internal val SEPARATE_PASS_DUMP = System.getProperty("fir.bench.dump.separate_pass", "false").toBooleanLenient()!!
private val APPEND_ERROR_REPORTS = System.getProperty("fir.bench.report.errors.append", "false").toBooleanLenient()!!
private val RUN_CHECKERS = System.getProperty("fir.bench.run.checkers", "false").toBooleanLenient()!!
private val USE_LIGHT_TREE = System.getProperty("fir.bench.use.light.tree", "false").toBooleanLenient()!!
private val DUMP_MEMORY = System.getProperty("fir.bench.dump.memory", "false").toBooleanLenient()!!

private val ASYNC_PROFILER_LIB = System.getProperty("fir.bench.use.async.profiler.lib")
private val ASYNC_PROFILER_START_CMD = System.getProperty("fir.bench.use.async.profiler.cmd.start")
private val ASYNC_PROFILER_STOP_CMD = System.getProperty("fir.bench.use.async.profiler.cmd.stop")
private val PROFILER_SNAPSHOT_DIR = System.getProperty("fir.bench.snapshot.dir") ?: "tmp/snapshots"

private val USE_PERF_STAT = System.getProperty("fir.bench.use.perf.stat", "true").toBooleanLenient()!!
private val PERF_LIB_PATH = System.getProperty("fir.bench.perf.lib")

private class PerfBenchListener(val helper: PerfStat) : BenchListener() {

    val statByStage = mutableMapOf<KClass<*>, StatResult>()
    val total get() = statByStage.values.reduce { acc, statResult -> acc.plus(statResult) }

    override fun before() {
        helper.resume()
    }

    override fun after(stageClass: KClass<*>) {
        helper.pause()
        statByStage.merge(stageClass, helper.retrieve()) { a, b -> a.plus(b) }
        helper.reset()
    }
}

private interface CLibrary : Library {
    fun getpid(): Int

    companion object {
        val INSTANCE = Native.load("c", CLibrary::class.java) as CLibrary
    }
}

fun isolate() {
    val isolatedList = System.getenv("DOCKER_ISOLATED_CPUSET")
    val othersList = System.getenv("DOCKER_CPUSET")
    println("Trying to isolate, SYS: '$othersList', ISO: '$isolatedList'")
    if (isolatedList != null && othersList != null) {
        val selfPid = CLibrary.INSTANCE.getpid()
        println("Will isolate, my pid: $selfPid")
        ProcessBuilder().command("bash", "-c", "ps -ae -o pid= | xargs -n 1 taskset -cap $othersList ").inheritIO().start().waitFor()
        ProcessBuilder().command("taskset", "-cap", isolatedList, "$selfPid").inheritIO().start().waitFor()
        ProcessBuilder().command("ps", "-o", "psr= $selfPid").inheritIO().start().waitFor()
    }
}

class FirResolveModularizedTotalKotlinTest : AbstractModularizedTest() {

    private lateinit var dump: MultiModuleHtmlFirDump
    private lateinit var bench: FirResolveBench
    private var bestStatistics: FirResolveBench.TotalStatistics? = null
    private var bestPass: Int = 0

    private val perfHelper = if (USE_PERF_STAT) PerfStat().also {
        it.init(PERF_LIB_PATH)
    } else null

    private var perfBenchListener: PerfBenchListener? = null

    private val asyncProfiler = if (ASYNC_PROFILER_LIB != null) {
        try {
            AsyncProfilerHelper.getInstance(ASYNC_PROFILER_LIB)
        } catch (e: ExceptionInInitializerError) {
            if (e.cause is ClassNotFoundException) {
                throw IllegalStateException("Async-profiler initialization error, make sure async-profiler.jar is on classpath", e.cause)
            }
            throw e
        }
    } else {
        null
    }

    private fun executeAsyncProfilerCommand(command: String?, pass: Int) {
        if (asyncProfiler != null) {
            require(command != null)
            fun String.replaceParams(): String =
                this.replace("\$REPORT_DATE", reportDateStr)
                    .replace("\$PASS", pass.toString())

            val snapshotDir = File(PROFILER_SNAPSHOT_DIR.replaceParams()).also { it.mkdirs() }
            val expandedCommand = command
                .replace("\$SNAPSHOT_DIR", snapshotDir.toString())
                .replaceParams()
            val result = asyncProfiler.execute(expandedCommand)
            println("PROFILER: $result")
        }
    }

    private fun runAnalysis(moduleData: ModuleData, environment: KotlinCoreEnvironment) {
        val project = environment.project
        val ktFiles = environment.getSourceFiles().sortedBy { it.virtualFilePath }

        val scope = GlobalSearchScope.filesScope(project, ktFiles.map { it.virtualFile })
            .uniteWith(TopDownAnalyzerFacadeForJVM.AllJavaSourcesInProjectScope(project))
        val librariesScope = ProjectScope.getLibrariesScope(project)
        val session = createSession(environment, scope, librariesScope, moduleData.qualifiedName)
        val scopeSession = ScopeSession()
        val processors = createAllCompilerResolveProcessors(session, scopeSession).let {
            if (RUN_CHECKERS) {
                it + FirCheckersResolveProcessor(session, scopeSession)
            } else {
                it
            }
        }

        val firProvider = session.firProvider as FirProviderImpl

        val firFiles = if (USE_LIGHT_TREE) {
            val lightTree2Fir = LightTree2Fir(session, firProvider.kotlinScopeProvider, stubMode = false)

            val allSourceFiles = moduleData.sources.flatMap {
                if (it.isDirectory) {
                    it.walkTopDown().toList()
                } else {
                    listOf(it)
                }
            }.filter {
                it.extension == "kt"
            }
            bench.buildFiles(lightTree2Fir, allSourceFiles)
        } else {
            val builder = RawFirBuilder(session, firProvider.kotlinScopeProvider, stubMode = false)
            bench.buildFiles(builder, ktFiles)
        }


        bench.processFiles(firFiles, processors)

        createMemoryDump(moduleData)

        val disambiguatedName = moduleData.disambiguatedName()
        dumpFir(disambiguatedName, moduleData, firFiles)
        dumpFirHtml(disambiguatedName, moduleData, firFiles)
    }

    private fun reportPerfStat(perfBenchListener: PerfBenchListener, statistics: FirResolveBench.TotalStatistics, pass: Int) {


        fun buildReport(out: Appendable) {

            fun RTableContext.buildRow(stageName: String, metrics: List<StatResult.Metric>) {
                row {
                    cell(stageName, align = LEFT)
                    for (metric in metrics) {
                        if (metric !is StatResult.LongMetric) continue
                        cell(buildString {
                            append(metric.value)
                        })
                    }
                }
            }
            printTable(out) {
                row {
                    cell("Stage", align = LEFT)
                    for (metric in perfBenchListener.total.metrics) {
                        if (metric !is StatResult.LongMetric) continue
                        cell(metric.name)
                    }
                }
                separator()
                for ((stage, result) in perfBenchListener.statByStage) {
                    buildRow(stage.simpleName!!, result.metrics)
                }
                separator()
                buildRow("Total", perfBenchListener.total.metrics)
            }
        }


        buildReport(System.out)

        PrintStream(
            FileOutputStream(
                reportDir().resolve("perf-$reportDateStr.log"),
                true
            )
        ).use { stream ->
            stream.println("====== Pass $pass ======")
            statistics.reportTimings(stream)
            buildReport(stream)

            stream.println()
            stream.println()
        }

    }

    private fun dumpFir(disambiguatedName: String, moduleData: ModuleData, firFiles: List<FirFile>) {
        if (!DUMP_FIR) return
        val dumpRoot = File(FIR_DUMP_PATH).resolve(disambiguatedName)
        firFiles.forEach {
            val directory = it.packageFqName.pathSegments().fold(dumpRoot) { file, name -> file.resolve(name.asString()) }
            directory.mkdirs()
            directory.resolve(it.name + ".fir").writeText(it.render())
        }
    }

    private val dumpedModules = mutableSetOf<String>()
    private fun ModuleData.disambiguatedName(): String {
        val baseName = qualifiedName
        var disambiguatedName = baseName
        var counter = 1
        while (!dumpedModules.add(disambiguatedName)) {
            disambiguatedName = "$baseName.${counter++}"
        }
        return disambiguatedName
    }

    private fun dumpFirHtml(disambiguatedName: String, moduleData: ModuleData, firFiles: List<FirFile>) {
        if (!DUMP_FIR) return
        dump.module(disambiguatedName) {
            firFiles.forEach(dump::indexFile)
            firFiles.forEach(dump::generateFile)
        }
    }

    override fun processModule(moduleData: ModuleData): ProcessorAction {
        val disposable = Disposer.newDisposable()


        val configuration = createDefaultConfiguration(moduleData)
        val environment = KotlinCoreEnvironment.createForTests(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)

        Extensions.getArea(environment.project)
            .getExtensionPoint(PsiElementFinder.EP_NAME)
            .unregisterExtension(JavaElementFinder::class.java)

        runAnalysis(moduleData, environment)

        Disposer.dispose(disposable)
        if (bench.hasFiles && FAIL_FAST) return ProcessorAction.STOP
        return ProcessorAction.NEXT
    }

    override fun beforePass(pass: Int) {
        if (DUMP_FIR) dump = MultiModuleHtmlFirDump(File(FIR_HTML_DUMP_PATH))
        System.gc()
        executeAsyncProfilerCommand(ASYNC_PROFILER_START_CMD, pass)
    }

    override fun afterPass(pass: Int) {
        val statistics = bench.getTotalStatistics()
        statistics.report(System.out, "Pass $pass")
        perfBenchListener?.let { reportPerfStat(it, statistics, pass) }

        perfHelper?.reset()

        saveReport(pass, statistics)
        if (statistics.totalTime < (bestStatistics?.totalTime ?: Long.MAX_VALUE)) {
            bestStatistics = statistics
            bestPass = pass
        }
        if (!SEPARATE_PASS_DUMP) {
            dumpedModules.clear()
        }
        if (FAIL_FAST) {
            bench.throwFailure()
        }

        executeAsyncProfilerCommand(ASYNC_PROFILER_STOP_CMD, pass)
    }

    override fun afterAllPasses() {
        val bestStatistics = bestStatistics ?: return
        printStatistics(bestStatistics, "Best pass: $bestPass")
        printErrors(bestStatistics)
    }

    private fun saveReport(pass: Int, statistics: FirResolveBench.TotalStatistics) {
        if (DUMP_FIR) dump.finish()
        printStatistics(statistics, "PASS $pass")
    }

    private fun printErrors(statistics: FirResolveBench.TotalStatistics) {
        PrintStream(
            FileOutputStream(
                reportDir().resolve("errors-$reportDateStr.log"),
                APPEND_ERROR_REPORTS
            )
        ).use(statistics::reportErrors)
    }

    private fun printStatistics(statistics: FirResolveBench.TotalStatistics, header: String) {
        PrintStream(
            FileOutputStream(
                reportDir().resolve("report-$reportDateStr.log"),
                true
            )
        ).use { stream ->
            statistics.report(stream, header)
            stream.println()
            stream.println()
        }
    }

    fun testTotalKotlin() {

        isolate()

        perfHelper?.open()

        for (i in 0 until PASSES) {
            println("Pass $i")
            perfBenchListener = perfHelper?.let { PerfBenchListener(it) }
            bench = FirResolveBench(withProgress = false, perfBenchListener)
            runTestOnce(i)
        }
        afterAllPasses()

        perfHelper?.close()
    }

    private fun createMemoryDump(moduleData: ModuleData) {
        if (!DUMP_MEMORY) return
        val name = "module_${moduleData.name}.hprof"
        val dir = File(FIR_MEMORY_DUMPS_PATH).also {
            it.mkdirs()
        }
        val filePath = dir.resolve(name).absolutePath
        createMemoryDump(filePath)
    }

    private fun createMemoryDump(filePath: String) {
        val server = ManagementFactory.getPlatformMBeanServer()
        val mxBean = ManagementFactory.newPlatformMXBeanProxy(
            server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean::class.java
        )
        mxBean.dumpHeap(filePath, true)
    }
}
