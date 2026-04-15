package org.monogram.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ProxyTypeModel

class LinkParserTest {

    private val parser = LinkParser()

    @Test
    fun `parsePrimary parses mtproto link 1`() {
        val link = "tg://proxy?server=example.com&port=443&secret=b16cbabb9281b9fc71f8352bf68523c2"

        val parsed = parser.parsePrimary(link)

        assertTrue(parsed is ParsedLink.AddProxy)
        parsed as ParsedLink.AddProxy
        assertEquals("example.com", parsed.server)
        assertEquals(443, parsed.port)
        assertTrue(parsed.type is ProxyTypeModel.Mtproto)
        val type = parsed.type as ProxyTypeModel.Mtproto
        assertEquals("b16cbabb9281b9fc71f8352bf68523c2", type.secret)
    }

    @Test
    fun `parsePrimary parses mtproto link 2`() {
        val link =
            "tg://proxy?server=example.com&port=443&secret=ddb16cbabb9281b9fc71f8352bf68523c2"

        val parsed = parser.parsePrimary(link)

        assertTrue(parsed is ParsedLink.AddProxy)
        parsed as ParsedLink.AddProxy
        assertEquals("example.com", parsed.server)
        assertEquals(443, parsed.port)
        assertTrue(parsed.type is ProxyTypeModel.Mtproto)
        val type = parsed.type as ProxyTypeModel.Mtproto
        assertEquals("ddb16cbabb9281b9fc71f8352bf68523c2", type.secret)
    }

    @Test
    fun `parsePrimary parses mtproto link 3`() {
        val link =
            "tg://proxy?server=example.com&port=443&secret=eeb16cbabb9281b9fc71f8352bf68523c26578616d706c652e636f6e"

        val parsed = parser.parsePrimary(link)

        assertTrue(parsed is ParsedLink.AddProxy)
        parsed as ParsedLink.AddProxy
        assertEquals("example.com", parsed.server)
        assertEquals(443, parsed.port)
        assertTrue(parsed.type is ProxyTypeModel.Mtproto)
        val type = parsed.type as ProxyTypeModel.Mtproto
        assertEquals("eeb16cbabb9281b9fc71f8352bf68523c26578616d706c652e636f6e", type.secret)
    }
}