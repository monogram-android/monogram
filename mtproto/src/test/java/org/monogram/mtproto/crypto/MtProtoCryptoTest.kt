package org.monogram.mtproto.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MtProtoCryptoTest {
    @Test
    fun `temporary key and iv match Telegram authorization sample`() {
        val derived = MtProtoKeyDerivation.temporaryAesKeyIv(
            newNonce = hex("BF8CB5BD9C5B4FE7CF24D64D281F89311576D53C0DA65A83267E57315414C9A6"),
            serverNonce = hex("63248F6748214EAB8A2F4CC876E11974"),
        )

        assertArrayEquals(
            hex("16F548177058E8D39C41CBAD4D419446BEB12EB9B8F5AD28EA824B8015F17D81"),
            derived.key,
        )
        assertArrayEquals(
            hex("C4D14166C1378E35C698460047DBB6075441BE9984611C28837357EBBF8CB5BD"),
            derived.iv,
        )

        val exposedKey = derived.key
        exposedKey.fill(0)
        assertArrayEquals(
            hex("16F548177058E8D39C41CBAD4D419446BEB12EB9B8F5AD28EA824B8015F17D81"),
            derived.key,
        )
    }

    @Test
    fun `digest primitives match standard known answers`() {
        val input = "abc".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(hex("A9993E364706816ABA3E25717850C26C9CD0D89D"), MtProtoKeyDerivation.sha1(input))
        assertArrayEquals(
            hex("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"),
            MtProtoKeyDerivation.sha256(input),
        )
    }

    @Test
    fun `AES IGE matches Telegram authorization sample and decrypts it`() {
        val key = hex("16F548177058E8D39C41CBAD4D419446BEB12EB9B8F5AD28EA824B8015F17D81")
        val iv = hex("C4D14166C1378E35C698460047DBB6075441BE9984611C28837357EBBF8CB5BD")
        val plaintext = hex("8BB20017894315B136AE5F4BAAD0F0BA20334342BA0D89B551A1143FC7A3666B")
        val ciphertext = hex("C334D313064174F443CE90E13C835FAEA6AE9677089A0781CC8C17ADC8FF5B50")

        assertArrayEquals(ciphertext, AesIge.encrypt(plaintext, key, iv))
        assertArrayEquals(plaintext, AesIge.decrypt(ciphertext, key, iv))
    }

    @Test
    fun `crypto boundaries reject invalid sizes without mutating input`() {
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoKeyDerivation.temporaryAesKeyIv(ByteArray(31), ByteArray(16))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoKeyDerivation.temporaryAesKeyIv(ByteArray(32), ByteArray(15))
        }

        val input = ByteArray(16) { it.toByte() }
        val original = input.copyOf()
        val key = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(32) { (it + 2).toByte() }
        val originalKey = key.copyOf()
        val originalIv = iv.copyOf()
        assertThrows(IllegalArgumentException::class.java) { AesIge.encrypt(input, ByteArray(31), ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { AesIge.encrypt(input, ByteArray(32), ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { AesIge.encrypt(ByteArray(0), ByteArray(32), ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { AesIge.encrypt(ByteArray(15), ByteArray(32), ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { AesIge.decrypt(input, ByteArray(31), ByteArray(32)) }
        AesIge.encrypt(input, key, iv)
        assertArrayEquals(original, input)
        assertArrayEquals(originalKey, key)
        assertArrayEquals(originalIv, iv)
    }

    @Test
    fun `derived key material isolates inputs and can be destroyed`() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(32) { (it + 32).toByte() }
        val expectedKey = key.copyOf()
        val expectedIv = iv.copyOf()
        val material = AesKeyIv(key, iv)

        key.fill(0)
        iv.fill(0)
        assertArrayEquals(expectedKey, material.key)
        assertArrayEquals(expectedIv, material.iv)

        material.close()
        assertThrows(IllegalStateException::class.java) { material.key }
        assertThrows(IllegalStateException::class.java) { material.iv }
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
