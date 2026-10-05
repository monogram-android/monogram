package org.monogram.root

import org.monogram.core.common.Outcome
import org.monogram.feature.settings.ui.ProxySession
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoTransportMode
import org.monogram.network.bridge.ProxyConfig

internal class ClientProxySession(private val client: MtprotoClient) : ProxySession {
    override fun configureProxy(config: ProxyConfig): Outcome<Unit> = client.configureProxy(config)
    override fun clearProxy(): Outcome<Unit> = client.clearProxy()
    override fun setTransportMode(mode: MtprotoTransportMode): Outcome<Unit> =
        client.setTransportMode(mode)

    override suspend fun testProxyConnection(): Outcome<Unit> = client.connect()
    override suspend fun pingProxy(config: ProxyConfig): Outcome<Long> = client.pingProxy(config)
}
