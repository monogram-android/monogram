package org.monogram.core.database

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.monogram.core.models.LiveLocationPhase
import org.monogram.core.models.LiveLocationSession
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

internal fun liveLocationKey(id: MessageId) = "live_location:${id.chatId.value}:${id.id}"

internal fun encodeLiveLocation(session: LiveLocationSession): String {
    session.requireValidStoredSession()
    return storedPayloadJson.encodeToString(
        StoredLiveLocation(
            chat = session.messageId.chatId.value,
            id = session.messageId.id,
            random = session.randomId,
            expires = session.expiresAt,
            phase = session.phase,
            revision = session.revision,
        )
    )
}

internal fun decodeLiveLocation(raw: String): LiveLocationSession = readStoredPayload {
    val value = storedPayloadJson.decodeFromString<StoredLiveLocation>(raw)
    require(value.version == 1)
    LiveLocationSession(
        MessageId(PeerId(value.chat), value.id),
        value.random,
        value.expires,
        value.phase,
        value.revision,
    ).also { it.requireValidStoredSession() }
}

private fun LiveLocationSession.requireValidStoredSession() {
    require(messageId.chatId.value != 0L && messageId.id > 0 && randomId != 0L && revision >= 0)
    require(expiresAt?.let { it > 0 } != false)
}

@Serializable
private data class StoredLiveLocation(
    @Required @SerialName("v") val version: Int = 1,
    val chat: Long,
    val id: Int,
    val random: Long,
    val expires: Long? = null,
    val phase: LiveLocationPhase,
    val revision: Long,
)
