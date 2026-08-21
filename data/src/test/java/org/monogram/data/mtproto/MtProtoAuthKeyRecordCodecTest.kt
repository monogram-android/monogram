package org.monogram.data.mtproto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MtProtoAuthKeyRecordCodecTest {
    @Test
    fun recordRoundTripPreservesMetadataAndDefensiveMaterial() {
        val material = material()
        val id = StoredMtProtoAuthKey.calculateId(material)
        val original = StoredMtProtoAuthKey.create(material, id, 42L, 1_783_001_185)
        material.fill(0)

        val encoded = MtProtoAuthKeyRecordCodec.encode(original)
        val decoded = MtProtoAuthKeyRecordCodec.decode(encoded)
        encoded.fill(0)
        original.close()
        try {
            val restored = decoded.copyMaterial()
            try {
                assertArrayEquals(material(), restored)
                assertEquals(id, decoded.id)
                assertEquals(42L, decoded.serverSalt)
                assertEquals(1_783_001_185, decoded.authKeyCreatedAt)
                assertEquals(1_783_001_185, decoded.serverTimeAnchorSeconds)
            } finally {
                restored.fill(0)
            }
        } finally {
            decoded.close()
        }
    }

    @Test
    fun decodesLegacyVersionOneRecordWithItsTimeAsBothAnchors() {
        val material = material()
        val id = StoredMtProtoAuthKey.calculateId(material)
        val legacy = java.nio.ByteBuffer.allocate(288).order(java.nio.ByteOrder.BIG_ENDIAN).apply {
            putInt(0x4d54414b)
            putInt(1)
            putLong(id)
            putLong(42L)
            putInt(1_783_001_185)
            putInt(material.size)
            put(material)
        }.array()
        material.fill(0)
        val decoded = MtProtoAuthKeyRecordCodec.decode(legacy)
        legacy.fill(0)
        try {
            assertEquals(1_783_001_185, decoded.authKeyCreatedAt)
            assertEquals(1_783_001_185, decoded.serverTimeAnchorSeconds)
        } finally {
            decoded.close()
        }
    }

    @Test
    fun rejectsWrongKeyIdAndMalformedRecords() {
        val material = material()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                StoredMtProtoAuthKey.create(material, 0L, 0L, 0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                MtProtoAuthKeyRecordCodec.decode(ByteArray(12))
            }
        } finally {
            material.fill(0)
        }
    }

    @Test
    fun scopesAreStableAndRejectUnsafeNames() {
        val scope = MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)
        assertEquals("v1_prod_default_dc2", scope.storageKey)
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoAuthKeyScope("../escape", MtProtoEnvironment.PRODUCTION, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 0)
        }
    }

    @Test
    fun encryptedBlobCodecRejectsTrailingAndOversizedData() {
        val encoded = EncryptedAuthKeyBlobCodec.encode(ByteArray(12) { 1 }, ByteArray(32) { 2 })
        EncryptedAuthKeyBlobCodec.decode(encoded).close()
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedAuthKeyBlobCodec.decode(encoded + 0.toByte())
        }
        encoded.fill(0)
    }

    private fun material(): ByteArray = ByteArray(StoredMtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }
}
