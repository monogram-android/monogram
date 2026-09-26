package org.monogram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class StartupSteps(
    val openDatabase: suspend () -> Unit,
    val createClient: suspend () -> Unit,
    val installImageCache: suspend () -> Unit,
    val installAppearance: suspend () -> Unit = {},
    val cleanup: suspend () -> Unit,
    val prewarm: suspend () -> Unit,
    val mediaMigration: suspend () -> Unit,
    val installDownloadSettings: suspend () -> Unit,
    val createMedia: suspend () -> Unit,
    val createPush: suspend () -> Unit,
    val startPush: () -> Unit = {},
    val startSponsor: () -> Unit = {},
    val startAppUpdate: () -> Unit = {},
)

internal suspend fun runFirstPaintStartup(
    background: CoroutineScope,
    steps: StartupSteps,
) {
    coroutineScope {
        val images = async { steps.installImageCache() }
        val appearance = async { steps.installAppearance() }
        val database = async { steps.openDatabase() }
        val client = async { steps.createClient() }
        database.await()
        background.launch { steps.cleanup() }
        client.await()
        background.launch { steps.prewarm() }
        background.launch { steps.mediaMigration() }
        steps.installDownloadSettings()
        steps.createMedia()
        steps.createPush()
        images.await()
        appearance.await()
    }
}

internal fun startAfterFirstPaint(steps: StartupSteps) {
    steps.startPush()
    steps.startSponsor()
    steps.startAppUpdate()
}