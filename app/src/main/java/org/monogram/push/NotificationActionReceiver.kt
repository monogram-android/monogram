package org.monogram.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.monogram.MonogramApp

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MonogramApp ?: return
        val pending = goAsync()
        app.launchWhenReady {
            try {
                val accountId = intent.getLongExtra(NotificationPresenter.EXTRA_ACCOUNT_USER_ID, 0L)
                val owner = notificationActionOwner(accountId, app.accountRegistry.all())
                val coordinator = owner?.let(app.pushRouter::coordinator)
                if (coordinator == null) pending.finish()
                else coordinator.handleNotificationAction(intent) { pending.finish() }
            } catch (_: Throwable) {
                pending.finish()
            }
        }.invokeOnCompletion { error ->
            if (error != null) pending.finish()
        }
    }
}

internal sealed interface NotificationAccountRoute {
    data object Reject : NotificationAccountRoute
    data object Open : NotificationAccountRoute
    data class Switch(val userId: Long) : NotificationAccountRoute
}

internal fun notificationAccountRoute(
    accountId: Long,
    activeUserId: Long?,
    accounts: List<org.monogram.AccountRecord>,
): NotificationAccountRoute {
    val owner =
        notificationActionOwner(accountId, accounts) ?: return NotificationAccountRoute.Reject
    return if (owner == activeUserId) NotificationAccountRoute.Open
    else NotificationAccountRoute.Switch(owner)
}

internal fun notificationActionOwner(
    accountId: Long, accounts: List<org.monogram.AccountRecord>
): Long? = if (accountId > 0L) accounts.firstOrNull { it.userId == accountId }?.userId
else if (accountId == 0L) accounts.singleOrNull()
    ?.takeIf { it.databaseName == "monogram.db" }?.userId
else null
