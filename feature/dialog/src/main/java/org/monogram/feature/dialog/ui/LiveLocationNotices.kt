package org.monogram.feature.dialog.ui

import android.icu.text.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.LiveLocationPhase
import org.monogram.core.models.LiveLocationSession
import org.monogram.core.models.MessageId
import org.monogram.core.ui.components.toUiMessage
import org.monogram.core.ui.loading.MonogramBusyIconButton
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.R

@Composable
internal fun LiveLocationControls(component: DialogComponent, onResume: (Int) -> Unit) {
    if (!component.liveLocationControlsAvailable) return
    val sessions by component.liveLocationSessions.collectAsStateWithLifecycle(emptyList())
    val errors by component.liveLocationErrors.collectAsStateWithLifecycle(emptyMap())
    val failure by component.liveLocationFailure.collectAsStateWithLifecycle(null)
    val busy by component.liveLocationBusy.collectAsStateWithLifecycle()
    val failed by component.liveLocationActionFailed.collectAsStateWithLifecycle()
    LiveLocationNotices(
        sessions,
        errors,
        failure,
        busy,
        failed,
        onResume,
        component::onStopLiveLocation,
        component::onJumpToMessage
    )
}

@Composable
internal fun LiveLocationNotices(
    sessions: List<LiveLocationSession>,
    errors: Map<MessageId, TelegramError>,
    failure: TelegramError?,
    busy: Set<Int>,
    failed: Set<Int>,
    onResume: (Int) -> Unit,
    onStop: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val visible =
        sessions.filter { it.phase !in setOf(LiveLocationPhase.STOPPED, LiveLocationPhase.EXPIRED) }
    if (visible.isEmpty()) return
    val locale = android.icu.util.ULocale.forLocale(LocalResources.current.configuration.locales[0])
    val time = remember(locale) {
        DateFormat.getTimeInstance(
            DateFormat.SHORT, locale
        )
    }
    val date = remember(locale) {
        DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT, locale
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .verticalScroll(rememberScrollState())
    ) {
        visible.forEach { session ->
            key(session.messageId) {
                val id = session.messageId.id
                val pending = session.phase == LiveLocationPhase.STOPPING || id in busy
                val error = errors[session.messageId] ?: failure
                val uncertain = session.phase == LiveLocationPhase.UNCERTAIN_STOP
                val status = when (session.phase) {
                    LiveLocationPhase.PAUSED -> stringResource(R.string.dialog_location_paused)
                    LiveLocationPhase.STOPPING -> stringResource(R.string.dialog_location_stopping)
                    LiveLocationPhase.UNCERTAIN_STOP -> stringResource(R.string.dialog_location_stop_uncertain)
                    else -> null
                }
                val until = session.expiresAt?.let {
                    val millis = it * 1000
                    stringResource(
                        R.string.dialog_location_until,
                        (if (android.text.format.DateUtils.isToday(millis)) time else date).format(
                            java.util.Date(millis)
                        )
                    )
                }
                val problem = error?.toUiMessage()
                    ?: if (id in failed) stringResource(org.monogram.core.ui.R.string.status_generic) else null
                val subtitle = listOfNotNull(problem ?: status, until).joinToString(" · ")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.testTag("live-location-$id")
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.LocationOn,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (uncertain || problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClick = { onOpen(id) }),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                stringResource(R.string.dialog_location_live),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (subtitle.isNotEmpty()) Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (uncertain || problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (session.phase == LiveLocationPhase.PAUSED || session.phase == LiveLocationPhase.ACTIVE && failure != null) {
                            IconButton(
                                onClick = { onResume(id) },
                                enabled = !pending,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.PlayArrow,
                                    stringResource(R.string.dialog_location_resume)
                                )
                            }
                        }
                        MonogramBusyIconButton(
                            onClick = { onStop(id) },
                            busy = pending,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("live-location-stop-$id"),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = if (uncertain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            status = stringResource(R.string.dialog_location_stopping)
                        ) {
                            Icon(
                                Icons.Outlined.StopCircle,
                                stringResource(R.string.dialog_location_stop)
                            )
                        }
                    }
                }
            }
        }
    }
}
