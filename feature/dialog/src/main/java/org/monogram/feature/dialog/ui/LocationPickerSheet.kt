package org.monogram.feature.dialog.ui

import android.icu.text.MeasureFormat
import android.icu.util.MeasureUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.shadow
import org.monogram.core.ui.components.SettingsCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.models.GeoPlace
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.feature.dialog.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationPickerSheet(
    place: GeoPlace?,
    locating: Boolean,
    mapTiles: org.monogram.network.http.MapTileStore?,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    onShareLive: ((Int) -> Unit)? = null,
) {
    var livePeriod by rememberSaveable { mutableIntStateOf(900) }
    var liveMode by rememberSaveable { mutableStateOf(false) }
    val sharingLive = liveMode && onShareLive != null
    val actionShapes = ButtonDefaults.shapes(
        shape = CircleShape,
        pressedShape = RoundedCornerShape(ExpressiveDefaults.PressRadius),
    )
    val mapWidth = (LocalConfiguration.current.screenWidthDp - 48).dp
    var closing by remember { mutableStateOf(false) }
    AppModalSheet(
        onDismissRequest = { closing = true }, visible = !closing, onExited = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
        ) {
            CreationSheetHeader(
                title = stringResource(R.string.dialog_location_pick_title),
                onDismiss = { closing = true })
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                when {
                    place != null -> {
                        LocationMap(
                            place = place, mapTiles = mapTiles, mapWidth = mapWidth,
                            mapHeight = 160.dp, mapShape = MaterialTheme.shapes.large,
                            marker = { LocationPin(Modifier.align(Alignment.Center)) },
                        )
                        Text(
                            place.coordinates, style = ExpressiveDefaults.tabularLabel(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    locating -> Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                stringResource(R.string.dialog_location_locating),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    else -> Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.dialog_location_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (place != null && locating) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (onShareLive != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                    ) {
                        listOf(R.string.dialog_location_pick_title, R.string.dialog_location_live)
                            .forEachIndexed { index, label ->
                                ToggleButton(
                                    checked = sharingLive == (index == 1),
                                    onCheckedChange = { liveMode = index == 1 },
                                    enabled = !locating,
                                    shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .heightIn(min = 56.dp),
                                ) { Text(stringResource(label)) }
                            }
                    }
                }
                if (sharingLive) {
                    val locale = LocalResources.current.configuration.locales[0]
                    val formatter = remember(locale) {
                        MeasureFormat.getInstance(
                            locale,
                            MeasureFormat.FormatWidth.WIDE
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    SettingsCard {
                        Column(Modifier.selectableGroup()) {
                            for (period in listOf(900, 3600, 28800)) {
                                val unit =
                                    if (period < 3600) MeasureUnit.MINUTE else MeasureUnit.HOUR
                                val value = if (period < 3600) period / 60 else period / 3600
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .selectable(
                                            livePeriod == period,
                                            enabled = !locating,
                                            role = Role.RadioButton,
                                            onClick = { livePeriod = period })
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text(
                                        formatter.formatMeasures(
                                            android.icu.util.Measure(
                                                value,
                                                unit
                                            )
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    RadioButton(
                                        selected = livePeriod == period,
                                        onClick = null,
                                        enabled = !locating
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.dialog_location_foreground),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 12.dp)
            ) {
                Button(
                    onClick = { if (sharingLive) onShareLive?.invoke(livePeriod) else onSend() },
                    enabled = !locating && (sharingLive || place != null), shapes = actionShapes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Text(stringResource(if (sharingLive) R.string.dialog_location_share_live else R.string.dialog_location_send_this))
                }
                TextButton(
                    onClick = { closing = true },
                    shapes = actionShapes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        }
    }
}

@Composable
private fun LocationPin(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(40.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LocationOn, stringResource(R.string.dialog_location_marker),
                tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp)
            )
        }
    }
}
