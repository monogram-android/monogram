package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.monogram.core.models.GeoPlace
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.feature.dialog.R

/** Location window: [onSend] confirms and closes, [onDismiss] cancels. */
@Composable
internal fun LocationPickerSheet(
    place: GeoPlace?,
    locating: Boolean,
    mapTiles: org.monogram.network.http.MapTileStore?,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The app-wide Shapes.small is 12.dp, so the 16.dp press radius is pinned here.
    val actionShapes = ButtonDefaults.shapes(
        shape = CircleShape,
        pressedShape = RoundedCornerShape(ExpressiveDefaults.PressRadius),
    )
    val mapWidth = (LocalConfiguration.current.screenWidthDp - 48).dp
    // 16:10; the bubble cards keep their own size.
    val mapHeight = mapWidth * 10f / 16f
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.dialog_location_pick_title),
                style = ExpressiveDefaults.titleSemiBold(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            when {
                place != null -> {
                    LocationMap(
                        place = place,
                        mapTiles = mapTiles,
                        mapWidth = mapWidth,
                        mapHeight = mapHeight,
                        mapShape = LocationMapCardShape,
                        modifier = Modifier.shadow(
                            elevation = 2.dp,
                            shape = LocationMapCardShape,
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        ),
                        marker = { LocationPin(Modifier.align(Alignment.Center)) },
                    )
                    Spacer(Modifier.height(16.dp))
                    CoordinatesChip(place.coordinates)
                }
                locating -> {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 24.dp))
                    Text(
                        text = stringResource(R.string.dialog_location_locating),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Box(modifier = Modifier.height(120.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.dialog_location_unavailable),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSend,
                enabled = place != null,
                shapes = actionShapes,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_location_send_this),
                    style = ExpressiveDefaults.actionSemiBold(),
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                shapes = actionShapes,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_cancel),
                    style = ExpressiveDefaults.actionSemiBold(),
                )
            }
        }
    }
}

private val LocationMapCardShape = RoundedCornerShape(28.dp)

@Composable
private fun LocationPin(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)),
        )
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = stringResource(R.string.dialog_location_marker),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
    }
}

@Composable
private fun CoordinatesChip(coordinates: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = coordinates,
                style = ExpressiveDefaults.tabularLabel(),
                maxLines = 1,
            )
        }
    }
}
