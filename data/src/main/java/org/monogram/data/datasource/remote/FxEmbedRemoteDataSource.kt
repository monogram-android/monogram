package org.monogram.data.datasource.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

class FxEmbedRemoteDataSource {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun getTwitterStatus(statusId: String): FxEmbedStatusResponse? {
        return requestJson("$TWITTER_BASE/2/status/$statusId")
    }

    suspend fun getBlueskyStatus(handle: String, rkey: String): FxEmbedStatusResponse? {
        return requestJson("$BLUESKY_BASE/2/status/$handle/$rkey")
    }

    private suspend inline fun <reified T> requestJson(urlString: String): T? =
        withContext(Dispatchers.IO) {
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

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "FxEmbed response code=${connection.responseCode} url=$urlString")
                    return@withContext null
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString<T>(response)
            } catch (e: Exception) {
                Log.w(TAG, "FxEmbed request failed for $urlString", e)
                null
            } finally {
                connection?.disconnect()
            }
        }

    @Serializable
    data class FxEmbedStatusResponse(
        val text: String? = null,
        val createdAt: String? = null,
        val author: FxEmbedAuthor? = null,
        val media: List<FxEmbedMedia>? = null
    )

    @Serializable
    data class FxEmbedAuthor(
        val name: String? = null,
        @SerialName("screen_name")
        val screenName: String? = null,
        val handle: String? = null,
        @SerialName("avatar_url")
        val avatarUrl: String? = null
    )

    @Serializable
    data class FxEmbedMedia(
        @SerialName("media_url")
        val mediaUrl: String? = null,
        val url: String? = null,
        val type: String? = null,
        val width: Int? = null,
        val height: Int? = null
    )

    private companion object {
        private const val TAG = "FxEmbedRemote"
        private const val TWITTER_BASE = "https://api.fxtwitter.com"
        private const val BLUESKY_BASE = "https://api.fxbsky.app"
        private const val USER_AGENT = "MonoGram-Android-App/1.0"
        private const val TIMEOUT_MS = 15_000
    }
}
