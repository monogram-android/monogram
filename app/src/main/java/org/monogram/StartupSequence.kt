package org.monogram

import kotlinx.coroutines.CancellationException
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
    val startAccounts: suspend () -> Unit = {},
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
        steps.openDatabase()
        steps.createClient()
        steps.cleanup()
        background.launch { steps.prewarm() }

        steps.installDownloadSettings()
        steps.createMedia()
        steps.createPush()
        images.await()
        appearance.await()
    }
}

internal suspend fun startAfterFirstPaint(steps: StartupSteps) {
    steps.startPush()
    steps.startAccounts()
    steps.startSponsor()
    steps.startAppUpdate()
}

internal suspend fun prepareStartupAccounts(
    accounts: List<AccountRecord>,
    activeUserId: Long?,
    onUnavailable: () -> Unit,
    prepare: suspend (AccountRecord) -> Unit,
): Set<Long> = accounts.mapNotNull { account ->
    try {
        prepare(account)
        account.userId
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (account.userId == activeUserId) throw error
        onUnavailable()
        null
    }
}.toSet()
