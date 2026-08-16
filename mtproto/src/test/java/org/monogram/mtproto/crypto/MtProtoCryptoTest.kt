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
    fun `auth key hashes and server salt match Telegram authorization sample`() {
        val authKey = hex(
            "8E1081A1B5CA1B399A9A9D7E08BB9A9182AB634F8C03F2A49F944E2F944A9C71EDBA61A32A70D3DADEB33752AE515B16B2D8E75039C40EBE18136775C3727372" +
                "A8DF486606D671FD63842DF0A44ACC31E68B7B1EC6A731A1DC5C748F0CB46AC00FDE363F0520B51D9B59EAE519EA511A8E8591FC7010DF0B07CDBAB04013DD85" +
                "172CB54555DC5C982EA0A5DCF4411E798D338B823161FD8C93100B7A426186B4C16F9113521081C8D2075872F4A0CF238034843DC01F2C26828721A2E2FFD93A" +
                "9B0142B8DF6355C43D9AEF5B448F1CC0D84E0E72A7FF494D4CC3B1650050DDEC5DC321ADA68E420F45098280CEAB58A1CBFAA60FFF3218E56B4741143AC5A6F0",
        )
        val newNonce = hex("BF8CB5BD9C5B4FE7CF24D64D281F89311576D53C0DA65A83267E57315414C9A6")
        val serverNonce = hex("63248F6748214EAB8A2F4CC876E11974")

        assertArrayEquals(hex("0314F29BC8B246FC"), MtProtoKeyDerivation.authKeyAuxHash(authKey))
        assertArrayEquals(hex("1107FDAD56DF3016"), MtProtoKeyDerivation.authKeyIdBytes(authKey))
        assertArrayEquals(
            hex("AA404B58DF404D8F363772B14CE5A56F"),
            MtProtoKeyDerivation.newNonceHash(newNonce, authKey, selector = 1),
        )
        assertArrayEquals(hex("DCA83ADAD47A014C"), MtProtoKeyDerivation.initialServerSalt(newNonce, serverNonce))
    }

    @Test
    fun `MTProto 2 message key and KDF2 vectors match independent calculation`() {
        val authKey = ByteArray(256) { it.toByte() }
        val msgKey = ByteArray(16) { it.toByte() }
        val plaintext = ByteArray(5) { (it + 1).toByte() }
        val padding = ByteArray(16) { (it + 16).toByte() }

        assertArrayEquals(
            hex("0ACFBD2723E37279852A9B62EC30E404"),
            MtProtoKeyDerivation.messageKey(authKey, plaintext, padding, x = 0),
        )
        MtProtoKeyDerivation.messageAesKeyIv(authKey, msgKey, x = 0).use { derived ->
            assertArrayEquals(
                hex("704ED09C8B41668AE8F99D244738F71DBDDC44469B6BBD4AA8573DD042BD059E"),
                derived.key,
            )
            assertArrayEquals(
                hex("4D266000A550EDABBF4C7CE40FD0043CC92230184CD317A5CC9C2482FD3B9318"),
                derived.iv,
            )
        }

        assertArrayEquals(
            hex("2A29DE7CE7CD667F16D4743BAFE019B5"),
            MtProtoKeyDerivation.messageKey(authKey, plaintext, padding, x = 8),
        )
        MtProtoKeyDerivation.messageAesKeyIv(authKey, msgKey, x = 8).use { derived ->
            assertArrayEquals(
                hex("217725799B245806458174A1FCFBC883906807B15033FDD0EA2B4D69CF9C364E"),
                derived.key,
            )
            assertArrayEquals(
                hex("669A6538917A4FA56CA32360A431C9160BE4AD887140980DAB91CE7BDC47FFBC"),
                derived.iv,
            )
        }
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
        assertThrows(IllegalArgumentException::class.java) { MtProtoKeyDerivation.authKeyIdBytes(ByteArray(255)) }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoKeyDerivation.messageAesKeyIv(ByteArray(256), ByteArray(16), x = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoKeyDerivation.messageKey(ByteArray(256), ByteArray(1), ByteArray(1), x = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoKeyDerivation.messageKey(ByteArray(256), ByteArray(1), ByteArray(0), x = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoKeyDerivation.newNonceHash(ByteArray(32), ByteArray(256), selector = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoKeyDerivation.newNonceHash(ByteArray(32), ByteArray(256), selector = 4)
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
