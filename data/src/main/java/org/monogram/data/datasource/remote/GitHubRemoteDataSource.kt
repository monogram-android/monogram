package org.monogram.data.datasource.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GitHubRemoteDataSource {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun getRecentCommits(
        branchOrSha: String,
        limit: Int
    ): Result<List<GitHubCommitResponse>> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val encodedBranch = URLEncoder.encode(branchOrSha, StandardCharsets.UTF_8)
            val url = URI("$BASE_URL/commits?sha=$encodedBranch&per_page=$limit").toURL()
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val responseCode = connection.responseCode
                Log.w(TAG, "GitHub commits request failed code=$responseCode ref=$branchOrSha")
                return@withContext Result.failure(IllegalStateException("GitHub response code=$responseCode"))
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            Result.success(json.decodeFromString<List<GitHubCommitResponse>>(responseText))
        } catch (error: Exception) {
            Log.w(TAG, "GitHub commits request failed ref=$branchOrSha", error)
            Result.failure(error)
        } finally {
            connection?.disconnect()
        }
    }

    @Serializable
    data class GitHubCommitResponse(
        val sha: String,
        @SerialName("html_url")
        val htmlUrl: String,
        val commit: GitHubCommitDetails
    )

    @Serializable
    data class GitHubCommitDetails(
        val message: String,
        val author: GitHubCommitAuthor? = null
    )

    @Serializable
    data class GitHubCommitAuthor(
        val name: String? = null,
        val date: String? = null
    )

    private companion object {
        private const val TAG = "GitHubRemote"
        private const val BASE_URL = "https://api.github.com/repos/monogram-android/monogram"
        private const val USER_AGENT = "MonoGram-Android-App/1.0"
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val TIMEOUT_MS = 15_000
    }
}
