package org.monogram.core.models

data class ContactSendSpec(
    val phone: String,
    val firstName: String,
    val lastName: String = "",
    val vcard: String = "",
) {
    val displayName: String get() = ContactCard(phone, firstName, lastName).displayName

    fun requireValid() {
        val digits = phone.removePrefix("+")
        require(digits.length in 3..15 && digits.all { it in '0'..'9' })
        require(firstName.isNotBlank() && firstName.length <= 256 && lastName.length <= 256)
        require(vcard.toByteArray(Charsets.UTF_8).size <= 65536)
    }
}

data class ContactImportSpec(
    val clientId: Long, val phone: String, val firstName: String, val lastName: String = ""
)

data class ContactImportMatch(val clientId: Long, val userId: PeerId)
data class ContactImportPopularity(val clientId: Long, val importers: Int)
data class ContactImportResult(
    val imported: List<ContactImportMatch>,
    val retryClientIds: List<Long>,
    val popular: List<ContactImportPopularity>,
)
