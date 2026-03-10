package org.monogram.presentation.features.stickers.ui.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun SetItem(
    isSelected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "containerColor"
    )
    
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(if (icon != null) 10.dp else 4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            icon()
        } else {
            content?.invoke()
        }
    }
}

@Composable
fun FloatingTabs(
    tabs: List<Triple<String, ImageVector, Int>>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Box(modifier = Modifier.padding(4.dp)) {
            val indicatorOffset by animateDpAsState(
                targetValue = (selectedTab * 64).dp,
                animationSpec = spring(stiffness = 500f),
                label = "indicatorOffset"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .size(width = 60.dp, height = 40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { (title, icon, index) ->
                    val selected = selectedTab == index
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "contentColor"
                    )

                    Box(
                        modifier = Modifier
                            .size(width = 60.dp, height = 40.dp)
                            .clip(CircleShape)
                            .clickable { onTabSelected(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
