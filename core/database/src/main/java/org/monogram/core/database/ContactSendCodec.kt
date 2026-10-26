package org.monogram.core.database

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.monogram.core.models.ContactSendSpec

fun encodeContactSend(spec: ContactSendSpec): String {
    spec.requireValid()
    return storedPayloadJson.encodeToString(
        StoredContactSend(
            phone = spec.phone,
            firstName = spec.firstName,
            lastName = spec.lastName,
            vcard = spec.vcard,
        )
    )
}

fun decodeContactSend(raw: String?): ContactSendSpec = readStoredPayload {
    val value = storedPayloadJson.decodeFromString<StoredContactSend>(requireNotNull(raw))
    require(value.version == 1 && value.userId == 0L)
    ContactSendSpec(value.phone, value.firstName, value.lastName, value.vcard)
        .also { it.requireValid() }
}

@Serializable
private data class StoredContactSend(
    @Required @SerialName("send_version") val version: Int = 1,
    @SerialName("p") val phone: String,
    @SerialName("f") val firstName: String,
    @SerialName("l") val lastName: String,
    @SerialName("c") val vcard: String,
    @Required @SerialName("u") val userId: Long = 0,
)
