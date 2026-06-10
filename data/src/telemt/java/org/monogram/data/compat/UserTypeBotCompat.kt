package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun TdApi.UserTypeBot.toDomainSupportsGuestQueries(): Boolean = false

internal fun buildTdUserTypeBot(
    canBeEdited: Boolean,
    canJoinGroups: Boolean,
    canReadAllGroupMessages: Boolean,
    hasMainWebApp: Boolean,
    hasTopics: Boolean,
    allowsUsersToCreateTopics: Boolean,
    canManageBots: Boolean,
    isInline: Boolean,
    inlineQueryPlaceholder: String,
    supportsGuestQueries: Boolean,
    needLocation: Boolean,
    canConnectToBusiness: Boolean,
    canBeAddedToAttachmentMenu: Boolean,
    activeUserCount: Int
): TdApi.UserTypeBot = TdApi.UserTypeBot(
    canBeEdited,
    canJoinGroups,
    canReadAllGroupMessages,
    hasMainWebApp,
    hasTopics,
    allowsUsersToCreateTopics,
    canManageBots,
    isInline,
    inlineQueryPlaceholder,
    needLocation,
    canConnectToBusiness,
    canBeAddedToAttachmentMenu,
    activeUserCount
)
