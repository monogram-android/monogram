package org.monogram.data.datasource.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.monogram.domain.models.webapp.OSMReverseResponse
import java.net.HttpURLConnection
import java.net.URI

class NominatimRemoteDataSource {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): OSMReverseResponse? {
        val url = "$BASE_URL/reverse?format=jsonv2&lat=$lat&lon=$lon&addressdetails=1"
        val responseText = makeHttpRequest(url) ?: return null

        return try {
            json.decodeFromString<OSMReverseResponse>(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse reverse geocode response", e)
            null
        }
    }

    private suspend fun makeHttpRequest(urlString: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URI(urlString).toURL()
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.e(TAG, "Nominatim response code=$responseCode url=$urlString")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nominatim network request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        private const val TAG = "NominatimRemote"
        private const val BASE_URL = "https://nominatim.openstreetmap.org"
        private const val USER_AGENT = "MonoGram-Android-App/1.0"
        private const val TIMEOUT_MS = 15_000
    }
}