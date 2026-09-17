package org.monogram.core.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.monogram.core.models.formatServiceMessage

fun serviceMessageTemplateId(kind: String): Int = when (kind) {
    "empty" -> R.string.service_empty
    "create" -> R.string.service_create
    "title" -> R.string.service_title
    "photo" -> R.string.service_photo
    "photo_remove" -> R.string.service_photo_remove
    "add" -> R.string.service_add
    "kick" -> R.string.service_kick
    "leave" -> R.string.service_leave
    "join_link" -> R.string.service_join_link
    "channel_create" -> R.string.service_channel_create
    "migrate_to" -> R.string.service_migrate_to
    "migrate_from" -> R.string.service_migrate_from
    "pin" -> R.string.service_pin
    "history_clear" -> R.string.service_history_clear
    "game_score" -> R.string.service_game_score
    "payment" -> R.string.service_payment
    "call" -> R.string.service_call
    "screenshot" -> R.string.service_screenshot
    "custom" -> R.string.service_custom
    "bot_allowed" -> R.string.service_bot_allowed
    "passport" -> R.string.service_passport
    "contact_signup" -> R.string.service_contact_signup
    "proximity" -> R.string.service_proximity
    "group_call" -> R.string.service_group_call
    "invite_call" -> R.string.service_invite_call
    "ttl" -> R.string.service_ttl
    "call_scheduled" -> R.string.service_call_scheduled
    "theme" -> R.string.service_theme
    "joined_request" -> R.string.service_joined_request
    "webview" -> R.string.service_webview
    "gift_premium" -> R.string.service_gift_premium
    "topic_create" -> R.string.service_topic_create
    "topic_edit" -> R.string.service_topic_edit
    "suggest_photo" -> R.string.service_suggest_photo
    "requested_peer" -> R.string.service_requested_peer
    "wallpaper" -> R.string.service_wallpaper
    "gift_code" -> R.string.service_gift_code
    "giveaway" -> R.string.service_giveaway
    "giveaway_results" -> R.string.service_giveaway_results
    "boost" -> R.string.service_boost
    "payment_refund" -> R.string.service_payment_refund
    "gift_stars" -> R.string.service_gift_stars
    "prize_stars" -> R.string.service_prize_stars
    "star_gift" -> R.string.service_star_gift
    "star_gift_unique" -> R.string.service_star_gift_unique
    "paid_refund" -> R.string.service_paid_refund
    "paid_price" -> R.string.service_paid_price
    "conference_call" -> R.string.service_conference_call
    "todo" -> R.string.service_todo
    "todo_add" -> R.string.service_todo_add
    "suggested_post" -> R.string.service_suggested_post
    "suggested_post_ok" -> R.string.service_suggested_post_ok
    "suggested_post_refund" -> R.string.service_suggested_post_refund
    "gift_ton" -> R.string.service_gift_ton
    "suggest_birthday" -> R.string.service_suggest_birthday
    else -> R.string.service_unknown
}

fun serviceMessageCatalog(resources: Resources): (String) -> String = { kind ->
    resources.getString(serviceMessageTemplateId(kind))
}

@Composable
fun localizedServiceMessage(raw: String, outgoing: Boolean = false): String {
    val resources = LocalContext.current.resources
    val you = stringResource(R.string.service_you)
    val someone = stringResource(R.string.service_someone)
    val catalog = remember(resources) { serviceMessageCatalog(resources) }
    return formatServiceMessage(
        raw = raw,
        youLabel = you,
        outgoing = outgoing,
        someoneLabel = someone,
        templateFor = catalog,
    )
}
