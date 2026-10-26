package org.monogram.feature.dialog.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.ContactImportAttempt
import org.monogram.core.models.ContactImportPhase
import org.monogram.core.models.ContactSendSpec

internal fun DialogExecutor.loadContactImport(spec: ContactSendSpec) {
    val cache = warmup ?: return
    if (snapshot().contactImportBusy) return
    val revision = ++contactImportRevision
    val epoch = cache.outgoingAccountEpoch()
    work.launch {
        try {
            val attempt = cache.contactImportAttempt(spec, epoch)
            if (revision == contactImportRevision && cache.outgoingAccountIsCurrent(epoch)) emit(
                Msg.ContactImportState(
                    attempt
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (revision == contactImportRevision && cache.outgoingAccountIsCurrent(epoch)) emit(
                Msg.ContactImportState(
                    null, error = TelegramError.parse("RPC 500: INTERNAL")
                )
            )
        }
    }
}

internal fun DialogExecutor.importContact(spec: ContactSendSpec, retry: Boolean) {
    val cache = warmup ?: return
    val current = snapshot()
    if (current.sending || current.contactImportBusy || !current.canView) return
    if (runCatching { spec.requireValid() }.isFailure) return
    val revision = ++contactImportRevision
    val epoch = cache.outgoingAccountEpoch()
    emit(Msg.ContactImportState(current.contactImportAttempt, busy = true))
    work.launch(start = CoroutineStart.UNDISPATCHED) {
        var retained: ContactImportAttempt? = null
        var completed: ContactImportAttempt? = null
        var error: TelegramError? = null
        try {
            val attempt =
                withContext(NonCancellable) { cache.beginContactImport(spec, epoch, retry) }
            retained = attempt
            currentCoroutineContext().ensureActive()
            check(cache.outgoingAccountIsCurrent(epoch))
            emit(Msg.ContactImportState(attempt, busy = true))
            completed = when (val result = client.importContacts(listOf(attempt.spec))) {
                is Outcome.Err -> {
                    error = result.telegramError; attempt.copy(phase = ContactImportPhase.UNCERTAIN)
                }

                is Outcome.Ok -> {
                    val value = result.value
                    val id = attempt.spec.clientId
                    require(value.imported.size <= 1 && value.retryClientIds.size <= 1 && value.popular.size <= 1)
                    require(value.imported.all { it.clientId == id && it.userId.value > 0 } && value.retryClientIds.all { it == id })
                    require(value.popular.all { it.clientId == id && it.importers >= 0 })
                    require(value.imported.isEmpty() || value.retryClientIds.isEmpty())
                    // Missing matches can reflect discovery privacy, not just unregistered phones.
                    val match = value.imported.singleOrNull()
                    attempt.copy(
                        phase = when {
                            match != null -> ContactImportPhase.IMPORTED
                            value.retryClientIds.isNotEmpty() -> ContactImportPhase.RETRY_REQUIRED
                            else -> ContactImportPhase.NOT_ADDED
                        }, userId = match?.userId
                    )
                }
            }
            withContext(NonCancellable) { cache.finishContactImport(spec, epoch, completed!!) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = TelegramError.parse("RPC 500: INTERNAL")
        } finally {
            withContext(NonCancellable) {
                try {
                    if (completed == null && retained != null) {
                        completed = retained!!.copy(phase = ContactImportPhase.UNCERTAIN)
                        cache.finishContactImport(spec, epoch, completed!!)
                    } else if (retained == null) completed = cache.contactImportAttempt(spec, epoch)
                } catch (_: Exception) {
                    completed = retained?.copy(phase = ContactImportPhase.UNCERTAIN)
                }
                if (revision == contactImportRevision && cache.outgoingAccountIsCurrent(epoch)) emit(
                    Msg.ContactImportState(completed, error = error)
                )
            }
        }
    }
}
