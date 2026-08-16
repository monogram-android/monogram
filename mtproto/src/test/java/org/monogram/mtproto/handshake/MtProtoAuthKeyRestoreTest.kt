package org.monogram.mtproto.handshake

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.crypto.MtProtoKeyDerivation

class MtProtoAuthKeyRestoreTest {
    @Test
    fun restoresValidatedAuthKeyWithDefensiveMaterial() {
        val material = ByteArray(MtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }
        val expected = material.copyOf()
        val id = keyId(material)
        val restored = MtProtoAuthKey.restore(material, id, 73L, 1_783_001_185)
        material.fill(0)
        try {
            val actual = restored.toByteArray()
            try {
                assertArrayEquals(expected, actual)
                assertEquals(id, restored.id)
                assertEquals(73L, restored.serverSalt)
                assertEquals(1_783_001_185, restored.createdAt)
            } finally {
                actual.fill(0)
            }
        } finally {
            expected.fill(0)
            restored.close()
        }
        assertThrows(IllegalStateException::class.java) { restored.toByteArray() }
    }

    @Test
    fun rejectsInvalidLengthAndKeyId() {
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoAuthKey.restore(ByteArray(255), 0L, 0L, 0)
        }
        val material = ByteArray(MtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                MtProtoAuthKey.restore(material, 0L, 0L, 0)
            }
        } finally {
            material.fill(0)
        }
    }

    private fun keyId(material: ByteArray): Long {
        val bytes = MtProtoKeyDerivation.authKeyIdBytes(material)
        return try {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
        } finally {
            bytes.fill(0)
        }
    }
}
