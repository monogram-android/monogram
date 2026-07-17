package org.monogram.data.datasource.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

internal fun createMonogramHttpClient(): HttpClient = HttpClient(Android) {
    applyMonogramHttpClientConfig()
}

internal fun createMonogramHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    applyMonogramHttpClientConfig()
}

private fun io.ktor.client.HttpClientConfig<*>.applyMonogramHttpClientConfig() {
    expectSuccess = false

    install(HttpTimeout) {
        requestTimeoutMillis = TIMEOUT_MS
        connectTimeoutMillis = TIMEOUT_MS
        socketTimeoutMillis = TIMEOUT_MS
    }

    defaultRequest {
        headers.append(HttpHeaders.UserAgent, USER_AGENT)
    }
}

private const val USER_AGENT = "MonoGram-Android-App/1.0"
private const val TIMEOUT_MS = 15_000L
