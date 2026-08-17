package org.monogram.mtproto.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramPasswordSrpTest {
    @Test
    fun createsTdLibCompatibleProofFromDeterministicVector() {
        val salt1 = hex("00112233445566778899aabbccddeeff")
        val salt2 = hex("ffeeddccbbaa99887766554433221100")
        val prime = DhParameterValidatorTest.PRIME.copyOf()
        val serverB = hex(SERVER_B)
        val originals = listOf(salt1.copyOf(), salt2.copyOf(), prime.copyOf(), serverB.copyOf())

        val proof = TelegramPasswordSrp.createProof(
            password = "пароль-Password-123",
            salt1 = salt1,
            salt2 = salt2,
            generator = 3,
            primeBytes = prime,
            serverBBytes = serverB,
            srpId = 0x0102030405060708L,
            entropy = EntropySource { destination ->
                destination.indices.forEach { destination[it] = ((it * 37 + 11) and 0xff).toByte() }
            },
        )

        assertEquals(0x0102030405060708L, proof.srpId)
        assertArrayEquals(hex(EXPECTED_A), proof.a)
        assertArrayEquals(hex("b429b8f6085db4da606da984f88e8525e506ba2f70858ccb796074bc44b4d618"), proof.m1)
        listOf(salt1, salt2, prime, serverB).zip(originals).forEach { (actual, original) ->
            assertArrayEquals(original, actual)
        }
    }

    @Test
    fun rejectsInvalidPrimeGeneratorAndServerB() {
        assertEquals(
            DhParameterFailure.INVALID_DH_GENERATOR,
            assertThrows(DhParameterException::class.java) { createProof(generator = 8) }.failure,
        )
        assertSrpFailure(TelegramPasswordSrpFailure.INVALID_SERVER_B_ENCODING) {
            createProof(serverB = ByteArray(247) { 1 })
        }
        assertSrpFailure(TelegramPasswordSrpFailure.INVALID_SERVER_B_ENCODING) {
            createProof(serverB = ByteArray(257) { 1 })
        }
        assertSrpFailure(TelegramPasswordSrpFailure.SERVER_B_OUT_OF_RANGE) {
            createProof(serverB = ByteArray(248))
        }
        assertSrpFailure(TelegramPasswordSrpFailure.SERVER_B_OUT_OF_RANGE) {
            createProof(serverB = DhParameterValidatorTest.PRIME)
        }
    }

    @Test
    fun rejectsEmptyPassword() {
        assertThrows(IllegalArgumentException::class.java) { createProof(password = "") }
    }

    private fun createProof(
        password: String = "password",
        generator: Int = 3,
        serverB: ByteArray = hex(SERVER_B),
    ) = TelegramPasswordSrp.createProof(
        password = password,
        salt1 = byteArrayOf(1),
        salt2 = byteArrayOf(2),
        generator = generator,
        primeBytes = DhParameterValidatorTest.PRIME,
        serverBBytes = serverB,
        srpId = 1L,
        entropy = EntropySource { it.fill(3) },
    )

    private fun assertSrpFailure(expected: TelegramPasswordSrpFailure, block: () -> Unit) {
        assertEquals(expected, assertThrows(TelegramPasswordSrpException::class.java, block).failure)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val SERVER_B =
            "5a95b46ddeccfb8cf0ad00e9a855b2fdaf8b13608833343e48917d7669cf14b39d10a3808ee6718e776fa30bfe30f0da" +
                "b4575d3a0d3e41439741a646cedd9975c432f2a3edb248915878c8e7a6bde570d17a6471f6ca960da503b1c74f9ec2090" +
                "e9a7ecb11054890b0a4d4162e7d43af852b43777c57f7dd1761392734eb251c8524aed3903e6361f0b76f2d865e50519a" +
                "14c4ff7749544f980b0eb8498ea0b23f97f5468fb1f96dec68de32402946732623eaadab6cbbf4c2a413eb40954c3e45f" +
                "3bdd85790b3a94dfe82457965f159f4508fbd2b82cab8e765a8d45275ae791d391320539a18e81ba0cf39522be4bfd8e09" +
                "e0f6f07a616ebad5ee826586d59"

        const val EXPECTED_A =
            "a59f784b1cd89bce3019e9a28b07b9c66fa428d2a0c68869a17888cb06ea730807bc33e838392faf1ce94c61448feadbe7" +
                "c69f691f2d09b1d717f825080da2a7f74755acb0c4518fbff92dfcd23af388e5637ab4856c225b015d0a9783352817d6fb" +
                "e0e6a4692bf9ddb48a2c797ac6b513891a0cdf1006370745395e72678e141863acf59be950db67093f9a3ff7ff3816ebec4" +
                "3a721330d59e5d1b92ac7aa16c4e64cba7c19e051151d5bb8df3c5e3438b294c34a2f935d35eb555269d6fa01e625055a" +
                "12407baaf6e3bbe9a124f8fe60bfcb54c4b99c16cafc1e7836f01b8e8e5780c80b863749770cfd6fb0423fd93c7cf07631" +
                "61598fac6ab7eaf5d9095f"
    }
}
