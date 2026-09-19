package org.monogram.core.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.core.common.SponsorRegistry

@Composable
fun rememberIsSponsor(peerId: Long?): Boolean {
    val ids by SponsorRegistry.sponsorIds.collectAsState()
    return peerId != null && peerId in ids
}

@Composable
fun SponsorBadge(
    peerId: Long?,
    size: Dp = 20.dp,
    gap: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    if (!rememberIsSponsor(peerId)) return
    if (gap > 0.dp) {
        Spacer(Modifier.width(gap))
    }
    Icon(
        imageVector = Icons.Outlined.WorkspacePremium,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(size),
    )
}
