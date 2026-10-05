package org.monogram.feature.settings.ui

import org.monogram.core.common.Outcome
import org.monogram.network.bridge.MtprotoTransportMode
import org.monogram.network.bridge.ProxyConfig

interface ProxySession {
    fun configureProxy(config: ProxyConfig): Outcome<Unit>
    fun clearProxy(): Outcome<Unit>
    fun setTransportMode(mode: MtprotoTransportMode): Outcome<Unit>
    suspend fun testProxyConnection(): Outcome<Unit>
    suspend fun pingProxy(config: ProxyConfig): Outcome<Long>
}
