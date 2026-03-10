package org.monogram.presentation.features.webapp.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.monogram.presentation.features.webapp.MainButtonState
import org.monogram.presentation.features.webapp.SecondaryButtonState

@Composable
fun MiniAppBottomBar(
    mainButtonState: MainButtonState,
    secondaryButtonState: SecondaryButtonState,
    bottomBarColor: Color?,
    onMainButtonClick: () -> Unit,
    onSecondaryButtonClick: () -> Unit
) {
    if (mainButtonState.isVisible || secondaryButtonState.isVisible) {
        Surface(
            color = bottomBarColor ?: MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                val isHorizontal =
                    secondaryButtonState.position == "left" || secondaryButtonState.position == "right"

                if (isHorizontal) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (secondaryButtonState.position == "left" && secondaryButtonState.isVisible) {
                            SecondaryButton(
                                state = secondaryButtonState,
                                onClick = onSecondaryButtonClick,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (mainButtonState.isVisible) {
                            MainButton(
                                state = mainButtonState,
                                onClick = onMainButtonClick,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (secondaryButtonState.position == "right" && secondaryButtonState.isVisible) {
                            SecondaryButton(
                                state = secondaryButtonState,
                                onClick = onSecondaryButtonClick,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (secondaryButtonState.position == "top" && secondaryButtonState.isVisible) {
                            SecondaryButton(
                                state = secondaryButtonState,
                                onClick = onSecondaryButtonClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (mainButtonState.isVisible) {
                            MainButton(
                                state = mainButtonState,
                                onClick = onMainButtonClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (secondaryButtonState.position == "bottom" && secondaryButtonState.isVisible) {
                            SecondaryButton(
                                state = secondaryButtonState,
                                onClick = onSecondaryButtonClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    } else {
        Spacer(Modifier.navigationBarsPadding())
    }
}
