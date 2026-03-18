package org.monogram.presentation.features.chats.currentChat.editor.video.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.presentation.features.chats.currentChat.editor.video.VideoFilter
import org.monogram.presentation.features.chats.currentChat.editor.video.getPresetVideoFilters


@Composable
fun VideoFilterControls(
    selectedFilter: VideoFilter?,
    onFilterSelect: (VideoFilter) -> Unit
) {
    val filters = getPresetVideoFilters()

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(filters) { filter ->
            val isSelected = (selectedFilter?.nameRes ?: filters.first().nameRes) == filter.nameRes

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(72.dp)
            ) {
                val filterName = stringResource(filter.nameRes)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (isSelected) Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(12.dp)
                            ) else Modifier
                        )
                        .clickable { onFilterSelect(filter) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filterName.take(1),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = filterName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
