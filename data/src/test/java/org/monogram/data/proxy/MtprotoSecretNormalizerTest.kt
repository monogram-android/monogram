package org.monogram.data.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.domain.proxy.MtprotoSecretNormalizer

class MtprotoSecretNormalizerTest {

    @Test
    fun `normalize keeps valid hex and lowercases it`() {
        val normalized = MtprotoSecretNormalizer.normalize("A1B2c3D4")
        assertEquals("a1b2c3d4", normalized)
    }

    @Test
    fun `normalize decodes base64url and converts to hex`() {
        val normalized = MtprotoSecretNormalizer.normalize("7v0mvBQ9yQrMNLtAYp5lHI1wZXRyb3ZpY2gucg")
        assertEquals("eefd26bc143dc90acc34bb40629e651c8d706574726f766963682e72", normalized)
    }

    @Test
    fun `normalize decodes base64 with padding and converts to hex`() {
        val normalized = MtprotoSecretNormalizer.normalize("AAE=")
        assertEquals("0001", normalized)
    }

    @Test
    fun `normalize rejects odd-length hex`() {
        val normalized = MtprotoSecretNormalizer.normalize("abc")
        assertNull(normalized)
    }

    @Test
    fun `normalize rejects invalid secret`() {
        val normalized = MtprotoSecretNormalizer.normalize("not a secret !")
        assertNull(normalized)
    }

    @Test
    fun `isValid returns false for blank input`() {
        assertFalse(MtprotoSecretNormalizer.isValid("   "))
    }
}
