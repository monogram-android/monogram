package org.monogram.network.bridge

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.network.bridge.session.DcTxtBootstrap

class DcTxtBootstrapTest {
    @Test
    fun parseAnswerExtractsTwoTxtParts() {
        val json = """
            {"Status":0,"Answer":[
              {"name":"apv3.stel.com.","type":16,"data":"short"},
              {"name":"apv3.stel.com.","type":16,"data":"longerpart"}
            ]}
        """.trimIndent()
        val parts = DcTxtBootstrap.parseAnswerParts(json)
        assertEquals(listOf("short", "longerpart"), parts)
    }

    @Test
    fun sidecarFreshWithinHour() {
        val now = 1_700_000_000_000L
        assertEquals(true, DcTxtBootstrap.sidecarFresh(now - 1_000L, now, DcTxtBootstrap.DOH_TTL_MS))
        assertEquals(false, DcTxtBootstrap.sidecarFresh(now - DcTxtBootstrap.DOH_TTL_MS, now, DcTxtBootstrap.DOH_TTL_MS))
        assertEquals(false, DcTxtBootstrap.sidecarFresh(0L, now, DcTxtBootstrap.DOH_TTL_MS))
    }
}
