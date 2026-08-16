package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoAuthKey
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.handshake.MtProtoHandshakeTransport
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

class MtProtoAuthKeyPersistenceTest {
    @Test
    fun savesAndRestoresHandshakeAuthKey() = runBlocking {
        val store = FakeStore()
        val persistence = MtProtoAuthKeyPersistence(store)
        val scope = MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)
        val material = material()
        val authKey = MtProtoAuthKey.restore(material, StoredMtProtoAuthKey.calculateId(material), 73L, 1_783_001_185)
        try {
            persistence.save(scope, authKey)
            material.fill(0)
            val loaded = persistence.load(scope) as PersistedMtProtoAuthKeyResult.Found
            loaded.authKey.use { restored ->
                val restoredMaterial = restored.toByteArray()
                try {
                    assertArrayEquals(material(), restoredMaterial)
                    assertEquals(authKey.id, restored.id)
                    assertEquals(73L, restored.serverSalt)
                } finally {
                    restoredMaterial.fill(0)
                }
            }
        } finally {
            authKey.close()
            material.fill(0)
            store.close()
        }
    }

    @Test
    fun preservesMissingAndCorruptLoadStates() = runBlocking {
        val store = FakeStore()
        val persistence = MtProtoAuthKeyPersistence(store)
        val scope = MtProtoAuthKeyScope("default", MtProtoEnvironment.TEST, 2)
        assertSame(PersistedMtProtoAuthKeyResult.Missing, persistence.load(scope))
        store.corrupt = true
        assertSame(PersistedMtProtoAuthKeyResult.Corrupt, persistence.load(scope))
    }

    @Test
    fun bootstrapEstablishesPersistsThenRestores() = runBlocking {
        val store = FakeStore()
        val persistence = MtProtoAuthKeyPersistence(store)
        var establishes = 0
        val bootstrap = MtProtoAuthKeySessionBootstrap(
            persistence,
            MtProtoAuthKeyEstablisher { _, _ ->
                establishes++
                authKey()
            },
        )
        val scope = MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)
        val first = bootstrap.loadOrEstablish(scope, UNUSED_TRANSPORT, config())
        try {
            assertEquals(MtProtoAuthKeySource.ESTABLISHED, first.source)
            assertEquals(1, establishes)
        } finally {
            first.authKey.close()
        }
        val second = bootstrap.loadOrEstablish(scope, UNUSED_TRANSPORT, config())
        try {
            assertEquals(MtProtoAuthKeySource.STORED, second.source)
            assertEquals(1, establishes)
        } finally {
            second.authKey.close()
            store.close()
        }
    }

    @Test
    fun bootstrapRejectsDcMismatchBeforeEstablishing() {
        var establishes = 0
        val bootstrap = MtProtoAuthKeySessionBootstrap(
            MtProtoAuthKeyPersistence(FakeStore()),
            MtProtoAuthKeyEstablisher { _, _ ->
                establishes++
                authKey()
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                bootstrap.loadOrEstablish(
                    MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 3),
                    UNUSED_TRANSPORT,
                    config(),
                )
            }
        }
        assertEquals(0, establishes)
    }

    @Test
    fun bootstrapClosesNewKeyWhenPersistenceFails() {
        val store = FakeStore().apply { failSave = true }
        lateinit var established: MtProtoAuthKey
        val bootstrap = MtProtoAuthKeySessionBootstrap(
            MtProtoAuthKeyPersistence(store),
            MtProtoAuthKeyEstablisher { _, _ -> authKey().also { established = it } },
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                bootstrap.loadOrEstablish(
                    MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2),
                    UNUSED_TRANSPORT,
                    config(),
                )
            }
        }
        assertThrows(IllegalStateException::class.java) { established.toByteArray() }
    }

    private class FakeStore : MtProtoAuthKeyStore, AutoCloseable {
        private var stored: StoredMtProtoAuthKey? = null
        var corrupt = false
        var failSave = false

        override suspend fun load(scope: MtProtoAuthKeyScope): MtProtoAuthKeyLoadResult {
            if (corrupt) return MtProtoAuthKeyLoadResult.Corrupt
            val current = stored ?: return MtProtoAuthKeyLoadResult.Missing
            val material = current.copyMaterial()
            return try {
                MtProtoAuthKeyLoadResult.Found(
                    StoredMtProtoAuthKey.create(material, current.id, current.serverSalt, current.createdAt),
                )
            } finally {
                material.fill(0)
            }
        }

        override suspend fun save(scope: MtProtoAuthKeyScope, authKey: StoredMtProtoAuthKey) {
            check(!failSave) { "storage unavailable" }
            stored?.close()
            val material = authKey.copyMaterial()
            stored = try {
                StoredMtProtoAuthKey.create(material, authKey.id, authKey.serverSalt, authKey.createdAt)
            } finally {
                material.fill(0)
            }
        }

        override suspend fun delete(scope: MtProtoAuthKeyScope) {
            stored?.close()
            stored = null
        }

        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
            delete(MtProtoAuthKeyScope(accountSlot, environment, 1))

        override fun close() {
            stored?.close()
            stored = null
        }
    }

    private fun material(): ByteArray = ByteArray(MtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }

    private fun authKey(): MtProtoAuthKey {
        val material = material()
        return try {
            MtProtoAuthKey.restore(material, StoredMtProtoAuthKey.calculateId(material), 73L, 1_783_001_185)
        } finally {
            material.fill(0)
        }
    }

    private fun config() = MtProtoHandshakeConfig(2, listOf("unused"))

    private companion object {
        val UNUSED_TRANSPORT = object : MtProtoHandshakeTransport {
            override suspend fun <R : TlObject> execute(method: TlMethod<R>): R = error("Not used")
        }
    }
}
