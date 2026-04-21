package org.monogram.presentation.settings.chatSettings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.WallpaperModel
import org.monogram.presentation.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WallpaperItem(
    wallpaper: WallpaperModel?,
    isSelected: Boolean,
    isBlurred: Boolean,
    blurIntensity: Int,
    isMoving: Boolean,
    dimming: Int,
    isGrayscale: Boolean,
    onClick: () -> Unit,
    onBlurClick: (Boolean) -> Unit,
    onBlurIntensityChange: (Int) -> Unit,
    onMotionClick: (Boolean) -> Unit,
    onDimmingChange: (Int) -> Unit,
    onGrayscaleClick: (Boolean) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val menuScale by animateFloatAsState(if (showMenu) 0.95f else 1f, label = "menuScale")
    val selectionScale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "selectionScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .scale(menuScale * selectionScale)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        Box(
            modifier = Modifier
                .size(80.dp, 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            WallpaperBackground(
                wallpaper = wallpaper,
                modifier = Modifier.fillMaxSize(),
                isBlurred = isBlurred,
                isMoving = isMoving,
                blurIntensity = blurIntensity,
                dimming = dimming,
                isGrayscale = isGrayscale,
                isChatSettings = true
            )

            @Suppress("RemoveRedundantQualifierName")
            (this@Column.AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            })

            MaterialTheme(
                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
            ) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.width(220.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.wallpaper_blur))
                                @Suppress("RemoveRedundantQualifierName")
                                (AnimatedVisibility(
                                    visible = isBlurred,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Slider(
                                            value = blurIntensity.toFloat(),
                                            onValueChange = {
                                                onBlurIntensityChange(it.toInt())
                                                if (it.toInt() == 0) {
                                                    onBlurClick(false)
                                                }
                                            },
                                            valueRange = 0f..100f,
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                })
                            }
                        },
                        trailingIcon = {
                            Switch(
                                checked = isBlurred,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            if (!isBlurred && blurIntensity == 0) {
                                onBlurIntensityChange(20)
                            }
                            onBlurClick(!isBlurred)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wallpaper_motion)) },
                        trailingIcon = {
                            Switch(
                                checked = isMoving,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            onMotionClick(!isMoving)
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.wallpaper_dimming))
                                @Suppress("RemoveRedundantQualifierName")
                                (AnimatedVisibility(
                                    visible = dimming > 0,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Slider(
                                            value = dimming.toFloat(),
                                            onValueChange = { onDimmingChange(it.toInt()) },
                                            valueRange = 0f..100f,
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                })
                            }
                        },
                        trailingIcon = {
                            Switch(
                                checked = dimming > 0,
                                onCheckedChange = { onDimmingChange(if (it) 30 else 0) }
                            )
                        },
                        onClick = {
                            onDimmingChange(if (dimming > 0) 0 else 30)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wallpaper_grayscale)) },
                        trailingIcon = {
                            Switch(
                                checked = isGrayscale,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            onGrayscaleClick(!isGrayscale)
                        }
                    )
                }
            }
        }
    }
}
