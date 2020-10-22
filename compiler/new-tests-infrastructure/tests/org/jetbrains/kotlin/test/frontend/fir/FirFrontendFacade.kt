/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir

import com.intellij.psi.PsiElementFinder
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.fir.analysis.FirAnalyzerFacade
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.session.FirJvmModuleInfo
import org.jetbrains.kotlin.fir.session.FirSessionFactory
import org.jetbrains.kotlin.test.components.ConfigurationComponents
import org.jetbrains.kotlin.test.components.getKtFilesForSourceFiles
import org.jetbrains.kotlin.test.model.*

class FirFrontendFacade(
    configurationComponents: ConfigurationComponents
) : FrontendFacade<FirSourceArtifact, FirDependencyProvider>(configurationComponents) {
    override fun analyze(module: TestModule, dependencyProvider: FirDependencyProvider): FirSourceArtifact {
        val environment = configurationComponents.kotlinCoreEnvironmentProvider.getKotlinCoreEnvironment(module)
        // TODO: add configurable parser

        val project = environment.project

        PsiElementFinder.EP.getPoint(project).unregisterExtension(JavaElementFinder::class.java)

        val ktFiles = configurationComponents.sourceFileProvider.getKtFilesForSourceFiles(module.files, project).values

        val sessionProvider = dependencyProvider.firSessionProvider

        val languageVersionSettings = module.languageVersionSettings
        val builtinsModuleInfo = dependencyProvider.builtinsModuleInfoForModule(module)
        createSessionForBuiltins(builtinsModuleInfo, sessionProvider, environment)
        createSessionForBinaryDependencies(module, sessionProvider, environment)

        val sourcesScope = TopDownAnalyzerFacadeForJVM.newModuleSearchScope(project, ktFiles)
        val sourcesModuleInfo = dependencyProvider.convertToFirModuleInfo(module)
        val session = FirSessionFactory.createJavaModuleBasedSession(
            sourcesModuleInfo,
            sessionProvider,
            sourcesScope,
            project,
            languageVersionSettings = languageVersionSettings
        )

        val firAnalyzerFacade = FirAnalyzerFacade(session, languageVersionSettings, ktFiles)
        val firFiles = firAnalyzerFacade.runResolution()
        val filesMap = firFiles.mapNotNull { firFile ->
            val testFile = module.files.firstOrNull { it.name == firFile.name } ?: return@mapNotNull null
            testFile to firFile
        }.toMap()

        return FirSourceArtifact(session, filesMap, firAnalyzerFacade)
    }

    private fun createSessionForBuiltins(
        builtinsModuleInfo: FirJvmModuleInfo,
        sessionProvider: FirProjectSessionProvider,
        environment: KotlinCoreEnvironment,
    ) {
        //For BuiltIns, registered in sessionProvider automatically
        val project = environment.project
        val allProjectScope = GlobalSearchScope.allScope(project)

        FirSessionFactory.createLibrarySession(
            builtinsModuleInfo, sessionProvider, allProjectScope, project,
            environment.createPackagePartProvider(allProjectScope)
        )
    }

    private fun createSessionForBinaryDependencies(
        module: TestModule,
        sessionProvider: FirProjectSessionProvider,
        environment: KotlinCoreEnvironment
    ) {
        val project = environment.project
        val librariesScope = ProjectScope.getLibrariesScope(project)
        val librariesModuleInfo = FirJvmModuleInfo.createForLibraries(module.name)
        FirSessionFactory.createLibrarySession(
            librariesModuleInfo,
            sessionProvider,
            librariesScope,
            project,
            environment.createPackagePartProvider(librariesScope)
        )
    }
}
