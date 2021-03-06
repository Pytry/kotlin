/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


// usages in build scripts are not tracked properly
@file:Suppress("unused")

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.project
import java.io.File
import java.lang.Character.isLowerCase
import java.lang.Character.isUpperCase
import java.nio.file.Files
import java.nio.file.Path

fun Task.dependsOnKotlinPluginInstall() {
    dependsOn(
        ":kotlin-allopen:install",
        ":kotlin-noarg:install",
        ":kotlin-sam-with-receiver:install",
        ":kotlin-android-extensions:install",
        ":kotlin-parcelize-compiler:install",
        ":kotlin-build-common:install",
        ":kotlin-compiler-embeddable:install",
        ":native:kotlin-native-utils:install",
        ":kotlin-util-klib:install",
        ":kotlin-util-io:install",
        ":kotlin-compiler-runner:install",
        ":kotlin-daemon-embeddable:install",
        ":kotlin-daemon-client:install",
        ":kotlin-gradle-plugin-api:install",
        ":kotlin-gradle-plugin:install",
        ":kotlin-gradle-plugin-model:install",
        ":kotlin-reflect:install",
        ":kotlin-annotation-processing-gradle:install",
        ":kotlin-test:kotlin-test-common:install",
        ":kotlin-test:kotlin-test-annotations-common:install",
        ":kotlin-test:kotlin-test-jvm:install",
        ":kotlin-test:kotlin-test-js:install",
        ":kotlin-test:kotlin-test-junit:install",
        ":kotlin-gradle-subplugin-example:install",
        ":kotlin-stdlib-common:install",
        ":kotlin-stdlib:install",
        ":kotlin-stdlib-jdk8:install",
        ":kotlin-stdlib-js:install",
        ":examples:annotation-processor-example:install",
        ":kotlin-script-runtime:install",
        ":kotlin-scripting-common:install",
        ":kotlin-scripting-jvm:install",
        ":kotlin-scripting-compiler-embeddable:install",
        ":kotlin-scripting-compiler-impl-embeddable:install",
        ":kotlin-test-js-runner:install",
        ":native:kotlin-klib-commonizer-embeddable:install"
    )
}

fun Project.projectTest(
    taskName: String = "test",
    parallel: Boolean = false,
    shortenTempRootName: Boolean = false,
    body: Test.() -> Unit = {}
): TaskProvider<Test> = getOrCreateTask(taskName) {
    doFirst {
        val commandLineIncludePatterns = (filter as? DefaultTestFilter)?.commandLineIncludePatterns ?: mutableSetOf()
        val patterns = filter.includePatterns + commandLineIncludePatterns
        if (patterns.isEmpty() || patterns.any { '*' in it }) return@doFirst
        patterns.forEach { pattern ->
            var isClassPattern = false
            val maybeMethodName = pattern.substringAfterLast('.')
            val maybeClassFqName = if (maybeMethodName.isFirstChar(::isLowerCase)) {
                pattern.substringBeforeLast('.')
            } else {
                isClassPattern = true
                pattern
            }

            if (!maybeClassFqName.substringAfterLast('.').isFirstChar(::isUpperCase)) {
                return@forEach
            }

            val classFileNameWithoutExtension = maybeClassFqName.replace('.', '/')
            val classFileName = "$classFileNameWithoutExtension.class"

            if (isClassPattern) {
                val innerClassPattern = "$pattern$*"
                if (pattern in commandLineIncludePatterns) {
                    commandLineIncludePatterns.add(innerClassPattern)
                    (filter as? DefaultTestFilter)?.setCommandLineIncludePatterns(commandLineIncludePatterns)
                } else {
                    filter.includePatterns.add(innerClassPattern)
                }
            }

            include {
                val path = it.path
                if (it.isDirectory) {
                    classFileNameWithoutExtension.startsWith(path)
                } else {
                    path == classFileName || (path.endsWith(".class") && path.startsWith("$classFileNameWithoutExtension$"))
                }
            }
        }
    }

    if (project.findProperty("kotlin.test.instrumentation.disable")?.toString()?.toBoolean() != true) {
        doFirst {
            val agent = tasks.findByPath(":test-instrumenter:jar")!!.outputs.files.singleFile
            val args = project.findProperty("kotlin.test.instrumentation.args")?.let { "=$it" }.orEmpty()
            jvmArgs("-javaagent:$agent$args")
        }
        dependsOn(":test-instrumenter:jar")
    }

    jvmArgs(
        "-ea",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:+UseCodeCacheFlushing",
        "-XX:ReservedCodeCacheSize=256m",
        "-Djna.nosys=true"
    )

    maxHeapSize = "1600m"
    systemProperty("idea.is.unit.test", "true")
    systemProperty("idea.home.path", intellijRootDir().canonicalPath)
    systemProperty("java.awt.headless", "true")
    environment("NO_FS_ROOTS_ACCESS_CHECK", "true")
    environment("PROJECT_CLASSES_DIRS", testSourceSet.output.classesDirs.asPath)
    environment("PROJECT_BUILD_DIR", buildDir)
    systemProperty("jps.kotlin.home", rootProject.extra["distKotlinHomeDir"]!!)
    systemProperty("kotlin.ni", if (rootProject.hasProperty("newInferenceTests")) "true" else "false")
    systemProperty("org.jetbrains.kotlin.skip.muted.tests", if (rootProject.hasProperty("skipMutedTests")) "true" else "false")

    if (Platform[202].orHigher()) {
        systemProperty("idea.ignore.disabled.plugins", "true")
    }

    var subProjectTempRoot: Path? = null
    doFirst {
        val teamcity = rootProject.findProperty("teamcity") as? Map<Any?, *>
        val systemTempRoot =
            // TC by default doesn't switch `teamcity.build.tempDir` to 'java.io.tmpdir' so it could cause to wasted disk space
            // Should be fixed soon on Teamcity side
            (teamcity?.get("teamcity.build.tempDir") as? String)
                ?: System.getProperty("java.io.tmpdir")
        systemTempRoot.let {
            val prefix = (project.name + "Project_" + taskName + "_").takeUnless { shortenTempRootName }
            subProjectTempRoot = Files.createTempDirectory(File(systemTempRoot).toPath(), prefix)
            systemProperty("java.io.tmpdir", subProjectTempRoot.toString())
        }
    }

    doLast {
        subProjectTempRoot?.let {
            try {
                delete(it)
            } catch (e: Exception) {
                project.logger.warn("Can't delete test temp root folder $it", e.printStackTrace())
            }
        }
    }

    if (parallel) {
        maxParallelForks =
            project.findProperty("kotlin.test.maxParallelForks")?.toString()?.toInt()
                ?: Math.max(Runtime.getRuntime().availableProcessors() / if (kotlinBuildProperties.isTeamcityBuild) 2 else 4, 1)
    }
    body()
}

private inline fun String.isFirstChar(f: (Char) -> Boolean) = isNotEmpty() && f(first())

inline fun <reified T : Task> Project.getOrCreateTask(taskName: String, noinline body: T.() -> Unit): TaskProvider<T> =
    if (tasks.names.contains(taskName)) tasks.named(taskName, T::class.java).apply { configure(body) }
    else tasks.register(taskName, T::class.java, body)

object TaskUtils {
    fun useAndroidSdk(task: Task) {
        task.useAndroidConfiguration(systemPropertyName = "android.sdk", configName = "androidSdk")
    }

    fun useAndroidJar(task: Task) {
        task.useAndroidConfiguration(systemPropertyName = "android.jar", configName = "androidJar")
    }

    fun useAndroidEmulator(task: Task) {
        task.useAndroidConfiguration(systemPropertyName = "android.sdk", configName = "androidEmulator")
    }
}

private fun Task.useAndroidConfiguration(systemPropertyName: String, configName: String) {
    val configuration = with(project) {
        configurations.getOrCreate(configName)
            .also {
                dependencies.add(
                    configName,
                    dependencies.project(":dependencies:android-sdk", configuration = configName)
                )
            }
    }

    dependsOn(configuration)

    if (this is Test) {
        doFirst {
            systemProperty(systemPropertyName, configuration.singleFile.canonicalPath)
        }
    }
}

fun Task.useAndroidSdk() {
    TaskUtils.useAndroidSdk(this)
}

fun Task.useAndroidJar() {
    TaskUtils.useAndroidJar(this)
}
