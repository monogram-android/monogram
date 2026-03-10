package org.monogram.domain.models.webapp

import kotlinx.serialization.Serializable

@Serializable
data class OSMReverseResponse(
    val place_id: Long? = null,
    val licence: String? = null,
    val osm_type: String? = null,
    val osm_id: Long? = null,
    val lat: String? = null,
    val lon: String? = null,
    val display_name: String? = null,
    val address: OSMAddress? = null,
    val boundingbox: List<String>? = null
)

@Serializable
data class OSMAddress(
    val road: String? = null,
    val suburb: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val county: String? = null,
    val state: String? = null,
    val postcode: String? = null,
    val country: String? = null,
    val country_code: String? = null,
    val house_number: String? = null,
    val neighbourhood: String? = null
) {
    val fullAddress: String
        get() = listOfNotNull(
            house_number,
            road,
            neighbourhood,
            suburb,
            city ?: town ?: village,
            postcode,
            country
        ).filter { it.isNotBlank() }.joinToString(", ")
}
