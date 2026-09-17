package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceMediaTest {
    @Test
    fun quizPayloadExposesAnswersVotesAndCorrectness() {
        val poll = ServiceMedia.poll(
            """{"q":"ready?","a":[{"t":"yes","c":1,"k":1,"v":7},{"t":"no","c":0,"k":0,"v":2}],"n":9,"z":1,"s":"because"}""",
        )!!

        assertEquals("ready?", poll.question)
        assertTrue(poll.isQuiz)
        assertTrue(poll.closed.not())
        assertEquals(9, poll.totalVoters)
        assertEquals("because", poll.solution)
        assertEquals(2, poll.answers.size)
        assertTrue(poll.answers[0].chosen)
        assertTrue(poll.answers[0].correct)
        assertEquals(7, poll.answers[0].voters)
        assertFalse(poll.answers[1].chosen)
        assertEquals("yes", poll.answers.first { it.correct }.text)
        assertTrue(poll.isAnswered)
    }

    @Test
    fun pollSharesUseTotalVotersWhenPublic() {
        val poll = ServiceMedia.poll(
            """{"q":"pick","a":[{"t":"a","v":3},{"t":"b","v":1}],"n":4,"o":1}""",
        )!!
        assertEquals(0.75f, poll.share(poll.answers[0]), 0.001f)
        assertEquals(0.25f, poll.share(poll.answers[1]), 0.001f)
        assertTrue(poll.showsShares)
        assertFalse(poll.isQuiz)
    }

    @Test
    fun liveGeoKeepsHeadingAndPeriod() {
        val place = ServiceMedia.geo("""{"lat":55.75,"long":37.61,"live":1,"p":900,"h":90}""")!!
        assertTrue(place.live)
        assertEquals(900, place.periodSeconds)
        assertEquals(90, place.heading)
        assertEquals("55.7500, 37.6100", place.coordinates)
    }

    @Test
    fun venueCarriesPlaceAndProviderDetails() {
        val venue = ServiceMedia.venue(
            """{"lat":55.75,"long":37.61,"t":"Red Square","a":"Moscow","p":"foursquare","i":"abc","y":"square"}""",
        )!!
        assertEquals("Red Square", venue.title)
        assertEquals("Moscow", venue.address)
        assertEquals("foursquare", venue.provider)
        assertEquals("abc", venue.venueId)
        assertEquals("square", venue.venueType)
    }

    @Test
    fun contactFallsBackToThePhoneNumberWhenNameless() {
        val named = ServiceMedia.contact("""{"p":"+79000000000","f":"Ada","l":"Lovelace","u":42}""")!!
        assertEquals("Ada Lovelace", named.displayName)
        assertEquals(42L, named.userId)

        val unnamed = ServiceMedia.contact("""{"p":"+79000000000"}""")!!
        assertEquals("+79000000000", unnamed.displayName)
    }

    @Test
    fun diceCarriesValueAndEmoticon() {
        val dice = ServiceMedia.dice("""{"v":4,"e":"🎲"}""")!!
        assertEquals(4, dice.value)
        assertEquals("🎲", dice.emoticon)
    }

    @Test
    fun malformedOrMissingPayloadsAreNullNotCrashes() {
        assertNull(ServiceMedia.poll(null))
        assertNull(ServiceMedia.poll(""))
        assertNull(ServiceMedia.poll("{not json"))
        assertNull(ServiceMedia.poll("""{"a":[]}"""))
        assertNull(ServiceMedia.geo("""{"lat":"north"}"""))
    }

    @Test
    fun messageAccessorsOnlyFireForTheirOwnKind() {
        val message = Message(
            id = MessageId(PeerId(1), 1),
            senderId = null,
            text = null,
            date = 0L,
            outgoing = false,
            mediaKind = "poll",
            fileName = """{"q":"q","a":[]}""",
        )
        assertEquals("q", message.poll?.question)
        assertNull(message.geoPlace)
        assertNull(message.venueCard)
        assertNull(message.contactCard)
        assertNull(message.dice)
    }
}

class ServiceMediaPreviewTest {
    private fun message(kind: String, payload: String) = Message(
        id = MessageId(PeerId(1), 7),
        senderId = null,
        text = null,
        date = 0L,
        outgoing = false,
        mediaKind = kind,
        fileName = payload,
    )

    @Test
    fun payloadIsNeverShownAsPreviewText() {
        val dice = message("dice", """{"v":3,"e":"🎲"}""")
        assertNull(dice.chatListPreviewSource())
        assertEquals("Dice", chatListMediaLabel("dice"))

        val poll = message("poll", """{"q":"ready?","a":[]}""")
        assertNull(poll.chatListPreviewSource())
        assertEquals("Poll", chatListMediaLabel("poll"))
    }

    @Test
    fun documentNamesStillBecomePreviewText() {
        val document = message("document", "report.pdf")
        assertEquals("report.pdf", document.chatListPreviewSource())
    }

    @Test
    fun allServiceKindsHaveALabel() {
        listOf("poll", "geo", "venue", "contact", "dice").forEach { kind ->
            assertNotNull("missing label for $kind", chatListMediaLabel(kind))
        }
    }
}
