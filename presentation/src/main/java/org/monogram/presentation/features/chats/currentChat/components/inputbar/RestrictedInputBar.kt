package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RestrictedInputBar(
    isCurrentUserRestricted: Boolean,
    restrictedUntilDate: Int
) {
    val restrictionDetails = remember(isCurrentUserRestricted, restrictedUntilDate) {
        if (!isCurrentUserRestricted) {
            null
        } else if (restrictedUntilDate <= 0) {
            RestrictionDetails.Permanent
        } else {
            RestrictionDetails.Until(formatRestrictedUntilDate(restrictedUntilDate))
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.input_error_not_allowed),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            AnimatedVisibility(
                visible = restrictionDetails != null,
                enter = fadeIn(animationSpec = tween(220)) + expandVertically(
                    animationSpec = tween(
                        220
                    )
                ),
                exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(
                    animationSpec = tween(
                        140
                    )
                )
            ) {
                val detailsText = when (restrictionDetails) {
                    is RestrictionDetails.Until -> stringResource(
                        R.string.logs_restricted_until,
                        restrictionDetails.value
                    )

                    RestrictionDetails.Permanent -> stringResource(R.string.logs_restricted_permanently)
                    null -> ""
                }

                Text(
                    text = detailsText,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private sealed interface RestrictionDetails {
    data class Until(val value: String) : RestrictionDetails
    data object Permanent : RestrictionDetails
}

private fun formatRestrictedUntilDate(epochSeconds: Int): String {
    val formatter = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault()
    )
    return formatter.format(Date(epochSeconds.toLong() * 1000L))
}
