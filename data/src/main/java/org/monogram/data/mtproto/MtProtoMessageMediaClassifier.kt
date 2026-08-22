package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.Game_616d2a0f4e
import org.monogram.mtproto.tl.generated.cloud.layer223.GeoPoint_126ad61cec
import org.monogram.mtproto.tl.generated.cloud.layer223.GeoPoint_9a65b6b51e
import org.monogram.mtproto.tl.generated.cloud.layer223.Poll_942021f3e0
import org.monogram.mtproto.tl.generated.cloud.layer223.WebPage_f814c33072

/** Coarse exhaustive media classification; keys are stable lookup handles where they exist. */
internal fun classifyMessageMedia(media: org.monogram.mtproto.tl.generated.cloud.layer223.MessageMedia?): Pair<String?, String?> {
    if (media == null) return null to null
    val document = (media as? org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDocument)
        ?.document as? org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
    if (document != null) return "DOCUMENT" to document.id.toString()
    val photo = (media as? org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPhoto)
        ?.photo as? org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316
    if (photo != null) return "PHOTO" to photo.id.toString()
    return when (media) {
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPoll ->
            "POLL" to ((media.poll as? Poll_942021f3e0)?.id?.toString())
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaGeo ->
            "GEO" to geoKey(media.geo)
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaGeoLive ->
            "GEO_LIVE" to geoKey(media.geo)
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaVenue ->
            "VENUE" to media.venueId.ifBlank { null }
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaContact ->
            "CONTACT" to media.phoneNumber.ifBlank { null }
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaWebPage -> {
            val page = media.webpage as? WebPage_f814c33072
            "WEBPAGE" to page?.url?.ifBlank { null }
        }
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaGame ->
            "GAME" to ((media.game as? Game_616d2a0f4e)?.id?.toString())
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDice ->
            "DICE" to (media.emoticon + ":" + media.value_)
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaStory ->
            "STORY" to media.id.toString()
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaInvoice ->
            "INVOICE" to media.title.ifBlank { null }
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaToDo -> "TODO" to null
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaGiveaway -> "GIVEAWAY" to null
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaGiveawayResults -> "GIVEAWAY_RESULTS" to null
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPaidMedia -> "PAID_MEDIA" to null
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaVideoStream -> "VIDEO_STREAM" to null
        is org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaUnsupported -> null to null
        org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaEmpty -> null to null
        else -> media::class.simpleName?.uppercase() to null
    }
}

private fun geoKey(geo: GeoPoint_9a65b6b51e): String? =
    (geo as? GeoPoint_126ad61cec)?.let { key -> key.lat.toString() + "," + key.long }
