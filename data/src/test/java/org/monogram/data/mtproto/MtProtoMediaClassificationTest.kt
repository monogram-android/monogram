package org.monogram.data.mtproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.GeoPoint_126ad61cec
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaUnsupported
import org.monogram.mtproto.tl.generated.cloud.layer223.Poll_942021f3e0
import org.monogram.mtproto.tl.generated.cloud.layer223.TextWithEntities_d094604bd3

class MtProtoMediaClassificationTest {
    private fun poll(id: Long): org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPoll {
        val text: org.monogram.mtproto.tl.generated.cloud.layer223.TextWithEntities_d70033c0f1 =
            TextWithEntities_d094604bd3("", emptyList())
        val results: org.monogram.mtproto.tl.generated.cloud.layer223.PollResults_32558a4319 =
            org.monogram.mtproto.tl.generated.cloud.layer223.PollResults_267c8c3226(
                min = false, results = null, totalVoters = null,
                recentVoters = null, solution = null, solutionEntities = null,
            )
        return org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPoll(
            poll = Poll_942021f3e0(
                id = id, closed = false, publicVoters = false, multipleChoice = false,
                quiz = false, question = text, answers = emptyList(),
                closePeriod = null, closeDate = null,
            ),
            results = results,
        )
    }

    @Test
    fun `classifies poll geo contact dice and unsupported variants`() {
        assertEquals("POLL" to "555", classifyMessageMedia(poll(555)))

        assertEquals(
            "GEO" to "1.5,-2.25",
            classifyMessageMedia(org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaGeo(GeoPoint_126ad61cec(long = -2.25, lat = 1.5, accessHash = 0L, accuracyRadius = null))),
        )
        assertEquals(
            "GEO_LIVE" to "0.0,0.0",
            classifyMessageMedia(org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaGeoLive(GeoPoint_126ad61cec(0.0, 0.0, 0L, accuracyRadius = null), heading = null, period = 60, proximityNotificationRadius = null)),
        )

        assertEquals(
            "CONTACT" to "+15551234",
            classifyMessageMedia(org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaContact("+15551234", "A", "B", "", 7L)),
        )

        assertEquals(
            "DICE" to ("\uD83C\uDFB2:5"),
            classifyMessageMedia(org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDice(value_ = 5, emoticon = "\uD83C\uDFB2", gameOutcome = null)),
        )

        assertEquals(null, classifyMessageMedia(MessageMediaUnsupported).first)
    }

    @Test
    fun `empty media classifies without type`() {
        assertEquals(null to null, classifyMessageMedia(MessageMediaEmpty))
    }
}
