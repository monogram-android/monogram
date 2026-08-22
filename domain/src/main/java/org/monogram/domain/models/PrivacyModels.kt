package org.monogram.domain.models

sealed class PrivacyRule {
    object AllowAll : PrivacyRule()
    object AllowContacts : PrivacyRule()
    object AllowNone : PrivacyRule()
    data class AllowUsers(val userIds: List<Long>) : PrivacyRule()
    data class AllowChatMembers(val chatIds: List<Long>) : PrivacyRule()
    object AllowBots : PrivacyRule()
    object AllowCloseFriends : PrivacyRule()
    object AllowPremium : PrivacyRule()
    object DisallowContacts : PrivacyRule()
    object DisallowBots : PrivacyRule()
    data class DisallowUsers(val userIds: List<Long>) : PrivacyRule()
    data class DisallowChatMembers(val chatIds: List<Long>) : PrivacyRule()
}

enum class PrivacyValue {
    EVERYBODY,
    MY_CONTACTS,
    NOBODY
}