/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.impl

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.test.TestConfiguration
import org.jetbrains.kotlin.test.services.*
import org.jetbrains.kotlin.test.builders.Constructor
import org.jetbrains.kotlin.test.directives.ComposedDirectivesContainer
import org.jetbrains.kotlin.test.directives.DirectivesContainer
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.configuration.EnvironmentConfigurator
import org.jetbrains.kotlin.test.util.TestDisposable

class TestConfigurationImpl(
    defaultsProvider: DefaultsProvider,
    assertions: Assertions,

    frontendFacades: List<Constructor<FrontendFacade<*>>>,
    frontend2BackendConverters: List<Constructor<Frontend2BackendConverter<*, *>>>,
    backendFacades: List<Constructor<BackendFacade<*, *>>>,

    frontendHandlers: List<Constructor<FrontendResultsHandler<*>>>,
    backendHandlers: List<Constructor<BackendInitialInfoHandler<*>>>,
    artifactsHandlers: List<Constructor<ArtifactsResultsHandler<*>>>,

    sourcePreprocessors: List<SourceFilePreprocessor>,
    environmentConfigurators: List<Constructor<EnvironmentConfigurator>>,
    directives: List<DirectivesContainer>
) : TestConfiguration() {
    override val rootDisposable: Disposable = TestDisposable()
    override val testServices: TestServices = TestServices()
    override val directives: DirectivesContainer

    init {
        val allDirectives = directives.toMutableList()

        testServices.apply {
            val sourceFileProvider = SourceFileProviderImpl(sourcePreprocessors)
            register(SourceFileProvider::class, sourceFileProvider)

            val configurators = environmentConfigurators.map { it.invoke(this) }
            configurators.mapTo(allDirectives) { it.directivesContainer }
            val environmentProvider = KotlinCoreEnvironmentProviderImpl(
                sourceFileProvider,
                rootDisposable,
                configurators
            )
            register(KotlinCoreEnvironmentProvider::class, environmentProvider)

            register(Assertions::class, assertions)
            register(DefaultsProvider::class, defaultsProvider)
        }

        this.directives = when (allDirectives.size) {
            0 -> DirectivesContainer.Empty
            1 -> allDirectives.single()
            else -> ComposedDirectivesContainer(allDirectives)
        }
    }

    private val frontendFacades: Map<FrontendKind<*>, FrontendFacade<*>> =
        frontendFacades
            .map { it.invoke(testServices) }
            .groupBy { it.frontendKind }
            .mapValues { it.value.singleOrNull() ?: manyFacadesError("frontend facades", "source -> ${it.key}") }

    private val frontend2BackendConverters: Map<FrontendKind<*>, Map<BackendKind<*>, Frontend2BackendConverter<*, *>>> =
        frontend2BackendConverters
            .map { it.invoke(testServices) }
            .groupBy { it.frontendKind }
            .mapValues { (frontendKind, converters) ->
                converters.groupBy { it.backendKind }.mapValues {
                    it.value.singleOrNull() ?: manyFacadesError("converters", "$frontendKind -> ${it.key}")
                }
            }

    private val backendFacades: Map<BackendKind<*>, Map<ArtifactKind<*>, BackendFacade<*, *>>> = backendFacades
        .map { it.invoke(testServices) }
        .groupBy { it.backendKind }
        .mapValues { (backendKind, facades) ->
            facades.groupBy { it.artifactKind }.mapValues {
                it.value.singleOrNull() ?: manyFacadesError("backend facades", "$backendKind -> ${it.key}")
            }
        }

    private val frontendHandlers: Map<FrontendKind<*>, List<FrontendResultsHandler<*>>> =
        frontendHandlers.map { it.invoke(testServices) }.groupBy { it.frontendKind }.withDefault { emptyList() }

    private val backendHandlers: Map<BackendKind<*>, List<BackendInitialInfoHandler<*>>> =
        backendHandlers.map { it.invoke(testServices) }.groupBy { it.backendKind }.withDefault { emptyList() }

    private val artifactsHandlers: Map<ArtifactKind<*>, List<ArtifactsResultsHandler<*>>> =
        artifactsHandlers.map { it.invoke(testServices) }.groupBy { it.artifactKind }.withDefault { emptyList() }

    private fun manyFacadesError(name: String, kinds: String): Nothing {
        error("Too many $name passed for $kinds configuration")
    }

    init {
        testServices.apply {
            this@TestConfigurationImpl.frontendFacades.values.forEach { register(it.additionalServices) }
            this@TestConfigurationImpl.frontend2BackendConverters.values.forEach { it.values.forEach { register(it.additionalServices) } }
            this@TestConfigurationImpl.backendFacades.values.forEach { it.values.forEach { register(it.additionalServices) } }
        }
    }

    override fun <R : ResultingArtifact.Source<R>> getFrontendFacade(frontendKind: FrontendKind<R>): FrontendFacade<R> {
        @Suppress("UNCHECKED_CAST")
        return frontendFacades.getValue(frontendKind) as FrontendFacade<R>
    }

    override fun <R : ResultingArtifact.Source<R>, I : ResultingArtifact.BackendInputInfo<I>> getConverter(
        frontendKind: FrontendKind<R>,
        backendKind: BackendKind<I>
    ): Frontend2BackendConverter<R, I> {
        @Suppress("UNCHECKED_CAST")
        return frontend2BackendConverters.getValue(frontendKind).getValue(backendKind) as Frontend2BackendConverter<R, I>
    }

    override fun <I : ResultingArtifact.BackendInputInfo<I>, A : ResultingArtifact.Binary<A>> getBackendFacade(
        backendKind: BackendKind<I>,
        artifactKind: ArtifactKind<A>
    ): BackendFacade<I, A> {
        @Suppress("UNCHECKED_CAST")
        return backendFacades.getValue(backendKind).getValue(artifactKind) as BackendFacade<I, A>
    }

    override fun <R : ResultingArtifact.Source<R>> getFrontendHandlers(frontendKind: FrontendKind<R>): List<FrontendResultsHandler<R>> {
        @Suppress("UNCHECKED_CAST")
        return frontendHandlers.getValue(frontendKind) as List<FrontendResultsHandler<R>>
    }

    override fun <I : ResultingArtifact.BackendInputInfo<I>> getBackendHandlers(backendKind: BackendKind<I>): List<BackendInitialInfoHandler<I>> {
        @Suppress("UNCHECKED_CAST")
        return backendHandlers.getValue(backendKind) as List<BackendInitialInfoHandler<I>>
    }

    override fun <A : ResultingArtifact.Binary<A>> getArtifactHandlers(artifactKind: ArtifactKind<A>): List<ArtifactsResultsHandler<A>> {
        @Suppress("UNCHECKED_CAST")
        return artifactsHandlers.getValue(artifactKind) as List<ArtifactsResultsHandler<A>>
    }

    override fun getAllFrontendHandlers(): List<FrontendResultsHandler<*>> {
        return frontendHandlers.values.flatten()
    }

    override fun getAllBackendHandlers(): List<BackendInitialInfoHandler<*>> {
        return backendHandlers.values.flatten()
    }

    override fun getAllArtifactHandlers(): List<ArtifactsResultsHandler<*>> {
        return artifactsHandlers.values.flatten()
    }
}
