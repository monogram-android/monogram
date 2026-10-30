package org.monogram.core.database

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.monogram.core.models.GeoPlace

fun encodeLocationSend(place: GeoPlace): String {
    place.requireValidSend()
    return storedPayloadJson.encodeToString(
        StoredLocationSend(
            latitude = place.latitude, longitude = place.longitude, live = place.live,
            period = place.periodSeconds, heading = place.heading,
        )
    )
}

fun decodeLocationSend(raw: String?): GeoPlace = readStoredPayload {
    val value = storedPayloadJson.decodeFromString<StoredLocationSend>(requireNotNull(raw))
    require(value.version == 1)
    GeoPlace(value.latitude, value.longitude, value.live, value.period, value.heading)
        .also { it.requireValidSend() }
}

private fun GeoPlace.requireValidSend() {
    require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
    require(if (live) periodSeconds == Int.MAX_VALUE || periodSeconds in 60..86400 else periodSeconds == 0)
    require(if (live) heading == null || heading in 1..360 else heading == null)
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class StoredLocationSend(
    @Required @SerialName("send_version") val version: Int = 1,
    @SerialName("lat") val latitude: Double,
    @SerialName("long") val longitude: Double,
    val live: Boolean,
    @SerialName("p") val period: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("h") val heading: Int? = null,
)
