package org.monogram.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.R

private val SkeletonAvatarSize = 112.dp
private val SkeletonCardCorner = 28.dp
private val SkeletonRowHeight = 56.dp

@Composable
fun ProfileSkeleton(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.status_connecting)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        MonogramPlaceholderCircle(size = SkeletonAvatarSize)
        Spacer(Modifier.height(16.dp))
        MonogramPlaceholder(
            modifier = Modifier.fillMaxWidth(0.42f).height(26.dp),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(8.dp))
        MonogramPlaceholder(
            modifier = Modifier.width(56.dp).height(20.dp),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.height(20.dp))
        ProfileSkeletonInfoCard()
        Spacer(Modifier.height(20.dp))
        ProfileSkeletonChipRow()
    }
}

@Composable
private fun ProfileSkeletonInfoCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SkeletonCardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            repeat(4) { index ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(start = 64.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SkeletonRowHeight)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MonogramPlaceholder(modifier = Modifier.size(32.dp), shape = CircleShape)
                    Column(modifier = Modifier.weight(1f)) {
                        MonogramPlaceholder(
                            modifier = Modifier.fillMaxWidth(0.46f).height(14.dp),
                            shape = RoundedCornerShape(6.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        MonogramPlaceholder(
                            modifier = Modifier.fillMaxWidth(0.28f).height(11.dp),
                            shape = RoundedCornerShape(6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSkeletonChipRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(88.dp, 80.dp, 76.dp, 68.dp).forEach { width ->
            MonogramPlaceholder(
                modifier = Modifier.width(width).height(32.dp),
                shape = CircleShape,
            )
        }
    }
}
