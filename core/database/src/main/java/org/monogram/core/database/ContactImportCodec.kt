package org.monogram.core.database

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import org.json.JSONObject
import org.monogram.core.models.ContactImportAttempt
import org.monogram.core.models.ContactImportPhase
import org.monogram.core.models.ContactImportSpec
import org.monogram.core.models.ContactSendSpec
import org.monogram.core.models.PeerId
import java.security.MessageDigest

internal fun contactImportMetaKey(spec: ContactSendSpec): String {
    spec.copy(vcard = "").requireValid()
    // Existing metadata keys depend on JSONObject's escaping and field order.
    val identity = JSONObject().put("send_version", 1).put("p", spec.phone)
        .put("f", spec.firstName).put("l", spec.lastName).put("c", "").put("u", 0).toString()
    val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    return "contact_import:" + bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun encodeContactImport(attempt: ContactImportAttempt): String =
    storedPayloadJson.encodeToString(
        StoredContactImport(
            id = attempt.spec.clientId, phone = attempt.spec.phone, first = attempt.spec.firstName,
            last = attempt.spec.lastName, phase = attempt.phase, user = attempt.userId?.value,
        )
    )

internal fun decodeContactImport(raw: String): ContactImportAttempt = readStoredPayload {
    val value = storedPayloadJson.decodeFromString<StoredContactImport>(raw)
    require(value.version == 1 && value.id != 0L)
    ContactSendSpec(value.phone, value.first, value.last).requireValid()
    val user = value.user?.let(::PeerId)
    require(if (value.phase == ContactImportPhase.IMPORTED) user != null && user.value > 0 else user == null)
    ContactImportAttempt(
        ContactImportSpec(value.id, value.phone, value.first, value.last), value.phase, user,
    )
}

@Serializable
private data class StoredContactImport(
    @Required val version: Int = 1,
    val id: Long,
    val phone: String,
    val first: String,
    val last: String,
    val phase: ContactImportPhase,
    val user: Long? = null,
)
