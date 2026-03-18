package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.editor.photo.ImageFilter
import org.monogram.presentation.features.chats.currentChat.editor.photo.getPresetFilters
import java.io.File

@Composable
fun FilterControls(
    imagePath: String,
    currentFilter: ImageFilter?,
    onFilterSelect: (ImageFilter?) -> Unit
) {
    val filters = remember { getPresetFilters() }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            FilterPreviewItem(
                imagePath = imagePath,
                name = stringResource(R.string.photo_editor_filter_original),
                filter = null,
                isSelected = currentFilter == null,
                onClick = { onFilterSelect(null) }
            )
        }
        items(filters) { filter ->
            FilterPreviewItem(
                imagePath = imagePath,
                name = stringResource(filter.nameRes),
                filter = filter,
                isSelected = currentFilter?.nameRes == filter.nameRes,
                onClick = { onFilterSelect(filter) }
            )
        }
    }
}

@Composable
fun FilterPreviewItem(
    imagePath: String,
    name: String,
    filter: ImageFilter?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (isSelected) Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(12.dp)
                    ) else Modifier
                )
                .clickable { onClick() }
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = filter?.let { ColorFilter.colorMatrix(it.colorMatrix) }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
