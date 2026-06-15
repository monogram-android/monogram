package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun buildSearchChats(query: String, limit: Int): TdApi.SearchChats =
    TdApi.SearchChats(query, limit)

internal fun buildSearchPublicChats(query: String): TdApi.SearchPublicChats =
    TdApi.SearchPublicChats(query)
