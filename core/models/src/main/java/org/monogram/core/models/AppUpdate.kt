package org.monogram.core.models

data class AppUpdateInfo(
    val version: String,
    val versionCode: Int,
    val description: String,
    val changelog: List<String>,
    val fileName: String,
    val fileSize: Long,
    val abi: String,
    val buildType: String,
    val chatId: Long,
    val messageId: Int,
    val mediaCacheKey: String,
    val downloadUrl: String? = null,
    val commit: String? = null,
)

data class ActionsArtifactName(
    val buildType: String,
    val abi: String,
    val version: String,
    val versionCode: Int,
    val commit: String? = null,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class Available(val info: AppUpdateInfo) : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Downloading(val info: AppUpdateInfo, val bytes: Long) : AppUpdateState
    data class ReadyToInstall(val info: AppUpdateInfo, val filePath: String) : AppUpdateState
    data class Error(val message: String, val info: AppUpdateInfo? = null) : AppUpdateState
}

object AppUpdate {
    const val CHANNEL_USERNAME = "monogram_apks"
    const val FALLBACK_CHAT_ID = -1003566234286L
    const val HISTORY_LIMIT = 20
    const val NO_UPDATE = "no_update"
    const val GITHUB_ARTIFACTS =
        "https://api.github.com/repos/monogram-android/monogram/actions/artifacts?per_page=100"
    const val GITHUB_ARTIFACT_ZIP =
        "https://nightly.link/monogram-android/monogram/actions/artifacts/%d.zip"

    private val VERSION_IN_CAPTION = Regex("""(\d+\.\d+\.\d+)\s*\((\d+)\)""")
    private val APK_NAME = Regex(
        """^monogram-(armeabi-v7a|arm64-v8a|x86_64|universal)-(\d+\.\d+\.\d+)-(debug|release|beta)\.apk$""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTIFACT_NAME = Regex(
        """^monogram-(debug|release|beta)-(armeabi-v7a|arm64-v8a|x86_64|universal)-(\d+\.\d+\.\d+)-(\d+)(?:-([0-9a-f]+))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_NUMBER = Regex("""^\d+\.\s*""")

    fun parseActionsArtifactName(name: String): ActionsArtifactName? {
        val match = ARTIFACT_NAME.matchEntire(name.trim()) ?: return null
        return ActionsArtifactName(
            buildType = match.groupValues[1].lowercase(),
            abi = match.groupValues[2].lowercase(),
            version = match.groupValues[3],
            versionCode = match.groupValues[4].toInt(),
            commit = match.groupValues.getOrNull(5)?.takeIf { it.isNotBlank() }?.lowercase(),
        )
    }

    fun compatibleBuildTypes(installed: String): Set<String> {
        val type = installed.lowercase()
        return if (type == "beta") setOf("beta", "release") else setOf(type)
    }

    fun inAppUpdatesEnabled(buildType: String): Boolean =
        buildType.lowercase() != "debug"

    fun actionsBetaAllowed(betaPref: Boolean, isSupporter: Boolean): Boolean =
        betaPref && isSupporter

    fun commitsMatch(remote: String?, local: String?): Boolean {
        val a = remote?.trim()?.lowercase().orEmpty()
        val b = local?.trim()?.lowercase().orEmpty()
        if (a.isEmpty() || b.isEmpty() || b == "unknown") return false
        return a == b || a.startsWith(b) || b.startsWith(a)
    }

    fun parseApkFileName(fileName: String): Triple<String, String, String>? {
        val match = APK_NAME.matchEntire(fileName.trim()) ?: return null
        return Triple(
            match.groupValues[1].lowercase(),
            match.groupValues[2],
            match.groupValues[3].lowercase(),
        )
    }

    fun parseCaptionVersion(text: String): Pair<String, Int>? {
        val match = VERSION_IN_CAPTION.find(text) ?: return null
        return match.groupValues[1] to match.groupValues[2].toInt()
    }

    fun parseChangelog(text: String): List<String> {
        val marker = text.indexOf("Changelog:", ignoreCase = true)
        if (marker < 0) return emptyList()
        return text.substring(marker + "Changelog:".length)
            .trimStart()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LEADING_NUMBER.replace(it, "") }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun fromMessage(message: Message): AppUpdateInfo? {
        if (message.mediaKind != "document") return null
        val fileName = message.fileName ?: return null
        if (!fileName.endsWith(".apk", ignoreCase = true)) return null
        val key = message.mediaCacheKey ?: return null
        val text = message.text.orEmpty()
        val captionVersion = parseCaptionVersion(text) ?: return null
        val parsedName = parseApkFileName(fileName)
        return AppUpdateInfo(
            version = captionVersion.first,
            versionCode = captionVersion.second,
            description = text,
            changelog = parseChangelog(text),
            fileName = fileName,
            fileSize = message.fileSize ?: 0L,
            abi = parsedName?.first ?: "universal",
            buildType = parsedName?.third.orEmpty(),
            chatId = message.id.chatId.value,
            messageId = message.id.id,
            mediaCacheKey = key,
        )
    }

    fun promptKey(info: AppUpdateInfo): String =
        "${info.versionCode}:${info.commit.orEmpty()}"

    fun latestActionsArtifact(
        artifacts: List<Triple<Long, String, Long>>,
        buildType: String,
        supportedAbis: List<String> = emptyList(),
    ): Triple<Long, String, Long>? {
        val parsed = artifacts.mapIndexedNotNull { index, artifact ->
            val name = parseActionsArtifactName(artifact.second) ?: return@mapIndexedNotNull null
            if (name.buildType !in compatibleBuildTypes(buildType)) return@mapIndexedNotNull null
            Triple(artifact to name, name.versionCode, -index)
        }
        if (parsed.isEmpty()) return null
        val newestCode = parsed.maxOf { it.second }
        val newest = parsed.filter { it.second == newestCode }
        val abis = supportedAbis.map { it.lowercase() } + "universal"
        for (abi in abis.distinct()) {
            newest.find { it.first.second.abi.equals(abi, ignoreCase = true) }?.let { return it.first.first }
        }
        return newest.maxByOrNull { it.third }?.first?.first
    }

    fun fromActionsArtifact(
        artifactId: Long,
        artifactName: String,
        zipSize: Long,
        supportedAbis: List<String>,
    ): AppUpdateInfo? {
        val parsed = parseActionsArtifactName(artifactName) ?: return null
        val abi = parsed.abi.ifBlank {
            supportedAbis.firstOrNull()?.lowercase() ?: "universal"
        }
        val fileName = "monogram-$abi-${parsed.version}-${parsed.buildType}.apk"
        val body = "${parsed.version} (${parsed.versionCode})"
        return fromGitHubAsset(
            fileName = fileName,
            fileSize = zipSize,
            downloadUrl = GITHUB_ARTIFACT_ZIP.format(artifactId),
            releaseBody = body,
        )?.copy(commit = parsed.commit)
    }

    fun fromGitHubAsset(
        fileName: String,
        fileSize: Long,
        downloadUrl: String,
        releaseBody: String,
    ): AppUpdateInfo? {
        if (!fileName.endsWith(".apk", ignoreCase = true)) return null
        val captionVersion = parseCaptionVersion(releaseBody) ?: return null
        val parsedName = parseApkFileName(fileName)
        return AppUpdateInfo(
            version = captionVersion.first,
            versionCode = captionVersion.second,
            description = releaseBody,
            changelog = parseChangelog(releaseBody),
            fileName = fileName,
            fileSize = fileSize,
            abi = parsedName?.first ?: "universal",
            buildType = parsedName?.third.orEmpty(),
            chatId = 0L,
            messageId = 0,
            mediaCacheKey = "gh:$fileName",
            downloadUrl = downloadUrl,
        )
    }

    fun select(
        infos: List<AppUpdateInfo>,
        supportedAbis: List<String>,
        buildType: String,
    ): AppUpdateInfo? {
        if (infos.isEmpty()) return null
        val wanted = compatibleBuildTypes(buildType)
        val typed = infos.filter {
            it.buildType.isEmpty() || it.buildType.lowercase() in wanted
        }.ifEmpty { infos }
        val newestCode = typed.maxOf { it.versionCode }
        val newest = typed.filter { it.versionCode == newestCode }
        for (abi in supportedAbis) {
            newest.find { it.abi.equals(abi, ignoreCase = true) }?.let { return it }
        }
        newest.find { it.abi.equals("universal", ignoreCase = true) }?.let { return it }
        return newest.firstOrNull()
    }

    fun afterCheck(
        authorized: Boolean,
        currentVersionCode: Int,
        selected: AppUpdateInfo?,
        currentCommit: String? = null,
    ): AppUpdateState {
        if (!authorized) return AppUpdateState.Idle
        if (selected == null) return AppUpdateState.Error(NO_UPDATE)
        if (selected.versionCode > currentVersionCode) return AppUpdateState.Available(selected)
        if (selected.versionCode == currentVersionCode &&
            selected.commit != null &&
            !commitsMatch(selected.commit, currentCommit)
        ) {
            return AppUpdateState.Available(selected)
        }
        return AppUpdateState.UpToDate
    }
}
