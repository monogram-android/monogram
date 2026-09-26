package org.monogram.update

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.models.AppUpdate
import org.monogram.core.models.AppUpdateInfo

@Serializable
data class GitHubArtifacts(
    val artifacts: List<GitHubArtifact> = emptyList(),
)

@Serializable
data class GitHubArtifact(
    val id: Long,
    val name: String,
    val expired: Boolean = false,
    @SerialName("size_in_bytes") val sizeInBytes: Long = 0,
)

suspend fun fetchGitHubArtifacts(client: HttpClient): Outcome<List<GitHubArtifact>> {
    return try {
        val response = client.get(AppUpdate.GITHUB_ARTIFACTS) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "Monogram")
        }
        if (!response.status.isSuccess()) {
            AppLog.warn("update", "github http=${response.status.value}")
            Outcome.Err("HTTP ${response.status.value}")
        } else {
            val all = response.body<GitHubArtifacts>().artifacts
            val live = all.filterNot { it.expired }
            AppLog.api(
                "update",
                "github http=${response.status.value} listed=${all.size} live=${live.size}",
            )
            Outcome.Ok(live)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Outcome.Err(error.message ?: "github check failed", error)
    }
}

fun gitHubUpdateInfo(
    artifacts: List<GitHubArtifact>,
    buildType: String,
    supportedAbis: List<String>,
): AppUpdateInfo? {
    val latest = AppUpdate.latestActionsArtifact(
        artifacts.map { Triple(it.id, it.name, it.sizeInBytes) },
        buildType,
        supportedAbis,
    ) ?: return null
    return AppUpdate.fromActionsArtifact(
        artifactId = latest.first,
        artifactName = latest.second,
        zipSize = latest.third,
        supportedAbis = supportedAbis,
    )
}

suspend fun downloadHttpApk(
    client: HttpClient,
    url: String,
    destination: File,
    onProgress: (Long) -> Unit,
): Outcome<File> {
    val partial = File(destination.parentFile, ".${destination.name}.part")
    return try {
        destination.parentFile?.mkdirs()
        client.prepareGet(url) {
            header("User-Agent", "Monogram")
        }.execute { response ->
            if (!response.status.isSuccess()) {
                error("HTTP ${response.status.value}")
            }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            FileOutputStream(partial).use { output ->
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
            if (!partial.renameTo(destination)) {
                error("cannot publish downloaded file")
            }
            Outcome.Ok(destination)
        }
    } catch (cancelled: CancellationException) {
        partial.delete()
        Outcome.Err("cancelled", cancelled)
    } catch (error: Exception) {
        partial.delete()
        Outcome.Err(error.message ?: "download failed", error)
    }
}

fun extractApkFromZip(zip: File, destDir: File, preferredNames: List<String>): File? {
    destDir.mkdirs()
    val available = linkedSetOf<String>()
    ZipInputStream(FileInputStream(zip)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            val name = File(entry.name).name
            if (name.endsWith(".apk", ignoreCase = true)) available += name
        }
    }
    val chosen = preferredNames.firstOrNull { wanted ->
        available.any { it.equals(wanted, ignoreCase = true) }
    } ?: available.firstOrNull() ?: return null
    ZipInputStream(FileInputStream(zip)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            val name = File(entry.name).name
            if (!name.equals(chosen, ignoreCase = true)) continue
            val out = File(destDir, name)
            FileOutputStream(out).use { output -> input.copyTo(output) }
            return out
        }
    }
    return null
}
