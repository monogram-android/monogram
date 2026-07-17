package org.monogram.data.datasource.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.monogram.domain.models.webapp.OSMReverseResponse

class NominatimRemoteDataSource(
    private val httpClient: HttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): OSMReverseResponse? {
        val responseText = makeHttpRequest(lat = lat, lon = lon) ?: return null

        return try {
            json.decodeFromString<OSMReverseResponse>(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse reverse geocode response", e)
            null
        }
    }

    private suspend fun makeHttpRequest(lat: Double, lon: Double): String? {
        return try {
            val response = httpClient.get("$BASE_URL/reverse") {
                parameter("format", "jsonv2")
                parameter("lat", lat)
                parameter("lon", lon)
                parameter("addressdetails", 1)
                accept(ContentType.Application.Json)
            }

            if (response.status == HttpStatusCode.OK) {
                response.bodyAsText()
            } else {
                Log.e(TAG, "Nominatim response code=${response.status.value} lat=$lat lon=$lon")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nominatim network request failed", e)
            null
        }
    }

    private companion object {
        private const val TAG = "NominatimRemote"
        private const val BASE_URL = "https://nominatim.openstreetmap.org"
    }
}