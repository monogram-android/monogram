package org.monogram.data.datasource.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class FxEmbedRemoteDataSource(
    private val httpClient: HttpClient
) : FixedPreviewRemoteDataSource {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override suspend fun getTwitterStatus(statusId: String): FxEmbedStatusResponse? {
        return requestJson("$TWITTER_BASE/2/status/$statusId")
    }

    override suspend fun getBlueskyStatus(handle: String, rkey: String): FxEmbedStatusResponse? {
        return requestJson("$BLUESKY_BASE/2/status/$handle/$rkey")
    }

    private suspend inline fun <reified T> requestJson(urlString: String): T? {
        return try {
            Log.d(TAG, "FxEmbed request url=$urlString")
            val response = httpClient.get(urlString) {
                accept(ContentType.Application.Json)
            }

            val responseCode = response.status.value
            Log.d(TAG, "FxEmbed response code=$responseCode url=$urlString")
            if (response.status != HttpStatusCode.OK) {
                Log.w(TAG, "FxEmbed response code=$responseCode url=$urlString")
                return null
            }

            val decoded = json.decodeFromString<T>(response.bodyAsText())
            Log.d(TAG, "FxEmbed decode success url=$urlString type=${T::class.simpleName}")
            decoded
        } catch (e: Exception) {
            Log.w(TAG, "FxEmbed request failed for $urlString", e)
            null
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
    }
}
