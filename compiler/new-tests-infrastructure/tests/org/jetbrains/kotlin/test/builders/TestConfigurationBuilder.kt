/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.builders

import org.jetbrains.kotlin.test.TestConfiguration
import org.jetbrains.kotlin.test.components.*
import org.jetbrains.kotlin.test.impl.TestConfigurationImpl
import org.jetbrains.kotlin.test.model.*

typealias Constructor<T> = () -> T

class TestConfigurationBuilder {
    lateinit var defaultsProvider: DefaultsProvider
    lateinit var assertions: Assertions

    private val frontendFacades: MutableList<Constructor<FrontendFacade<*>>> = mutableListOf()
    private val frontend2BackendConverters: MutableList<Constructor<Frontend2BackendConverter<*, *>>> = mutableListOf()
    private val backendFacades: MutableList<Constructor<BackendFacade<*, *>>> = mutableListOf()

    private val frontendHandlers: MutableList<Constructor<FrontendResultsHandler<*>>> = mutableListOf()
    private val backendHandlers: MutableList<Constructor<BackendInitialInfoHandler<*>>> = mutableListOf()
    private val artifactsHandlers: MutableList<Constructor<ArtifactsResultsHandler<*>>> = mutableListOf()

    private val sourcePreprocessors: MutableList<SourceFilePreprocessor> = mutableListOf()

    inline fun globalDefaults(init: DefaultsProviderBuilder.() -> Unit) {
        defaultsProvider = DefaultsProviderBuilder().apply(init).build()
    }

    fun useFrontendFacades(vararg constructor: Constructor<FrontendFacade<*>>) {
        frontendFacades += constructor
    }

    fun useBackendFacades(vararg constructor: Constructor<BackendFacade<*, *>>) {
        backendFacades += constructor
    }

    fun useFrontend2BackendConverters(vararg constructor: Constructor<Frontend2BackendConverter<*, *>>) {
        frontend2BackendConverters += constructor
    }

    fun useFrontendHandlers(vararg constructor: Constructor<FrontendResultsHandler<*>>) {
        frontendHandlers += constructor
    }

    fun useBackendHandlers(vararg constructor: Constructor<BackendInitialInfoHandler<*>>) {
        backendHandlers += constructor
    }

    fun useArtifactsHandlers(vararg constructor: Constructor<ArtifactsResultsHandler<*>>) {
        artifactsHandlers += constructor
    }

    fun useSourcePreprocessor(vararg preprocessors: SourceFilePreprocessor) {
        sourcePreprocessors += preprocessors
    }

    fun build(): TestConfiguration {
        return TestConfigurationImpl(
            defaultsProvider,
            assertions,
            frontendFacades,
            frontend2BackendConverters,
            backendFacades,
            frontendHandlers,
            backendHandlers,
            artifactsHandlers,
            sourcePreprocessors
        )
    }
}

inline fun testConfiguration(init: TestConfigurationBuilder.() -> Unit): TestConfiguration {
    return TestConfigurationBuilder().apply(init).build()
}
