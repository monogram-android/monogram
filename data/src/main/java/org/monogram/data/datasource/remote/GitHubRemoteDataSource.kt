package org.monogram.data.datasource.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GitHubRemoteDataSource(
    private val httpClient: HttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun getRecentCommits(
        branchOrSha: String,
        limit: Int
    ): Result<List<GitHubCommitResponse>> {
        return try {
            val response = httpClient.get("$BASE_URL/commits") {
                parameter("sha", branchOrSha)
                parameter("per_page", limit)
                accept(ContentType.parse("application/vnd.github+json"))
                header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }

            if (response.status != HttpStatusCode.OK) {
                val responseCode = response.status.value
                Log.w(TAG, "GitHub commits request failed code=$responseCode ref=$branchOrSha")
                return Result.failure(IllegalStateException("GitHub response code=$responseCode"))
            }

            Result.success(json.decodeFromString<List<GitHubCommitResponse>>(response.bodyAsText()))
        } catch (error: Exception) {
            Log.w(TAG, "GitHub commits request failed ref=$branchOrSha", error)
            Result.failure(error)
        }
    }

    /** Latest published GitHub release with its downloadable assets, for the MTProto update channel. */
    suspend fun getLatestRelease(): Result<GitHubReleaseResponse> {
        return try {
            val response = httpClient.get("$BASE_URL/releases/latest") {
                accept(ContentType.parse("application/vnd.github+json"))
                header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }

            if (response.status != HttpStatusCode.OK) {
                val responseCode = response.status.value
                Log.w(TAG, "GitHub release request failed code=$responseCode")
                return Result.failure(IllegalStateException("GitHub response code=$responseCode"))
            }

            Result.success(json.decodeFromString<GitHubReleaseResponse>(response.bodyAsText()))
        } catch (error: Exception) {
            Log.w(TAG, "GitHub release request failed", error)
            Result.failure(error)
        }
    }

    @Serializable
    data class GitHubReleaseResponse(
        @SerialName("tag_name")
        val tagName: String,
        val name: String? = null,
        val body: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<GitHubReleaseAsset> = emptyList(),
    )

    @Serializable
    data class GitHubReleaseAsset(
        val id: Long,
        val name: String,
        val size: Long,
        @SerialName("browser_download_url")
        val browserDownloadUrl: String,
    )

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
        private const val GITHUB_API_VERSION = "2026-03-10"
    }
}
