package org.monogram.core.database

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.models.ContactImportPhase
import org.monogram.core.models.ServiceMedia

class StoredPayloadCompatibilityTest {
    @Test
    fun legacyPollRemainsSendableAndRenderableWithOpaqueAnswerOptions() {
        val raw =
            """{"q":"Pick \\\"one\\\"","a":[{"t":"First","o":"30"},{"t":"Second","o":"31"}],"z":true,"m":false,"o":false,"send":{"version":1,"correct":1,"solution":"Why"},"future":true}"""
        val spec = decodePollSend(raw)
        assertEquals(1, spec.correctAnswer)
        val encoded = encodePollSend(spec)
        assertEquals(spec, decodePollSend(encoded))
        val rendered = ServiceMedia.poll(encoded)!!
        assertEquals(spec.question, rendered.question)
        assertArrayEquals(byteArrayOf(49), rendered.answers[1].option)
        assertTrue(rendered.isQuiz)
        assertTrue(runCatching { decodePollSend(raw.replace("\"31\"", "\"32\"")) }.isFailure)
    }

    @Test
    fun legacyContactKeepsEscapedTextVcardAndRenderingShape() {
        val spec =
            decodeContactSend("""{"send_version":1,"p":"+15555550123","f":"A/B","l":"Contact","c":"line1\nline2","u":0}""")
        val encoded = encodeContactSend(spec)
        assertEquals(spec, decodeContactSend(encoded))
        assertNotNull(ServiceMedia.contact(encoded))
        assertEquals("line1\nline2", spec.vcard)
    }

    @Test
    fun locationsRetainOptionalHeadingAndLongLivedSessionIdentity() {
        val place =
            decodeLocationSend("""{"send_version":1,"lat":55.5,"long":37.5,"live":false,"p":0}""")
        assertFalse(
            storedPayloadJson.parseToJsonElement(encodeLocationSend(place)).jsonObject.containsKey(
                "h"
            )
        )
        assertEquals(place, ServiceMedia.geo(encodeLocationSend(place)))
        val session =
            decodeLiveLocation("""{"v":1,"chat":7,"id":37,"random":9223372036854775807,"expires":null,"phase":"PAUSED","revision":2}""")
        assertEquals(Long.MAX_VALUE, session.randomId)
        assertEquals(session, decodeLiveLocation(encodeLiveLocation(session)))
        assertTrue(encodeLiveLocation(session).contains("\"expires\":null"))
    }

    @Test
    fun legacyDraftDefaultsAndImportPhaseSurviveTypedDecoding() {
        val draft =
            decodeDraftState("""{"version":1,"chatId":7,"threadId":0,"text":"Draft","entities":null,"replyTo":0,"date":10,"noWebpage":false,"unsupported":false,"revision":2,"dirty":true,"remoteDate":9}""")
        assertFalse(draft.markdown)
        assertFalse(draft.discardingUnsupported)
        assertEquals(draft, decodeDraftState(encodeDraftState(draft)))
        val attempt =
            decodeContactImport("""{"version":1,"id":9223372036854775807,"phone":"+15555550123","first":"Fixture","last":"","phase":"IMPORTED","user":42}""")
        assertEquals(ContactImportPhase.IMPORTED, attempt.phase)
        assertEquals(Long.MAX_VALUE, attempt.spec.clientId)
        assertEquals(attempt, decodeContactImport(encodeContactImport(attempt)))
    }

    @Test
    fun missingVersionsAndInvalidStoredStateAreRejected() {
        assertTrue(runCatching { decodeContactSend("""{"p":"+15555550123","f":"Fixture","l":"","c":"","u":0}""") }.isFailure)
        assertTrue(runCatching { decodeLocationSend("""{"send_version":2,"lat":0,"long":0,"live":false,"p":0}""") }.isFailure)
        assertTrue(runCatching { decodeLocationSend("""{"send_version":1,"lat":91,"long":0,"live":false,"p":0}""") }.isFailure)
        val invalid = runCatching { decodeDraftState("{}") }.exceptionOrNull()
        assertTrue(invalid is StoredPayloadException)
        assertNull(invalid?.cause)
        assertTrue(runCatching { decodeContactImport("""{"version":1,"id":1,"phone":"+15555550123","first":"Fixture","last":"","phase":"IMPORTED","user":null}""") }.isFailure)
    }
}
