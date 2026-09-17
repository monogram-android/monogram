@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val SettingsChoicePadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
private val SettingsChoiceIconSize = 18.dp
private val SettingsChoiceIconSpacing = 6.dp

data class SettingsChoice(
    val label: String,
    val icon: ImageVector? = null,
)

@Composable
fun SettingsChoiceGroup(
    options: List<SettingsChoice>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val shapes = when {
                options.size == 1 -> ToggleButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight)
                index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val selected = index == selectedIndex
            ToggleButton(
                checked = selected,
                onCheckedChange = { onSelect(index) },
                modifier = Modifier
                    .weight(1f)
                    .height(ButtonDefaults.MediumContainerHeight)
                    .semantics { role = Role.RadioButton },
                shapes = shapes,
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = SettingsChoicePadding,
            ) {
                val leading = if (selected) Icons.Outlined.Check else option.icon
                if (leading != null) {
                    Icon(
                        imageVector = leading,
                        contentDescription = null,
                        modifier = Modifier.size(SettingsChoiceIconSize),
                    )
                    Spacer(Modifier.size(SettingsChoiceIconSpacing))
                }
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
