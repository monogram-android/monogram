package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoNetworkStatisticsRepositoryTest {
    @Test
    fun `accumulates traffic per network type and reports usage`() = runBlocking {
        val store = FakeKeyValueStore()
        var type = NetworkType.WIFI
        val repository = MtProtoNetworkStatisticsRepositoryImpl(store, networkType = { type })

        repository.trafficListener.onTraffic(100, 0)
        repository.trafficListener.onTraffic(0, 400)
        type = NetworkType.MOBILE
        repository.trafficListener.onTraffic(50, 25)

        val usage = repository.getNetworkUsage()
        assertEquals(100L, usage.wifi.sent)
        assertEquals(400L, usage.wifi.received)
        assertEquals("MTProto", usage.wifi.details.single().name)
        assertEquals(50L, usage.mobile.sent)
        assertEquals(25L, usage.mobile.received)
        assertEquals(0L, usage.roaming.sent)
        assertTrue(usage.other.details.isEmpty())
    }

    @Test
    fun `disabled statistics ignore incoming traffic`() = runBlocking {
        val store = FakeKeyValueStore()
        val repository = MtProtoNetworkStatisticsRepositoryImpl(store, networkType = { NetworkType.WIFI }, recordingScope = CoroutineScope(Dispatchers.Unconfined))

        repository.setNetworkStatisticsEnabled(false)
        repository.trafficListener.onTraffic(100, 100)

        val usage = repository.getNetworkUsage()
        assertEquals(0L, usage.wifi.sent)
        assertEquals(0L, usage.wifi.received)
    }

    @Test
    fun `reset clears all recorded buckets`() = runBlocking {
        val store = FakeKeyValueStore()
        val repository = MtProtoNetworkStatisticsRepositoryImpl(store, networkType = { NetworkType.WIFI }, recordingScope = CoroutineScope(Dispatchers.Unconfined))

        repository.trafficListener.onTraffic(10, 20)
        assertTrue(repository.resetNetworkStatistics())

        val usage = repository.getNetworkUsage()
        assertEquals(0L, usage.wifi.sent)
        assertEquals(0L, usage.wifi.received)
    }
}
