package org.monogram.feature.dialog.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.feature.dialog.R

/**
 * A map app that can show a coordinate. [pinUri] is the app's own scheme or a plain `geo:` URI;
 * [routeUri] is null when the app exposes no route entry point.
 */
internal data class MapTarget(
    val name: String,
    val packageName: String,
    val pinUri: (Double, Double, String) -> Uri,
    val routeUri: ((Double, Double) -> Uri)?,
)

internal val mapTargets: List<MapTarget> = listOf(
    MapTarget(
        name = "Google Maps",
        packageName = "com.google.android.apps.maps",
        pinUri = { lat, lon, address ->
            Uri.parse("geo:$lat,$lon?q=${Uri.encode(address.ifBlank { "$lat,$lon" })}")
        },
        routeUri = { lat, lon -> Uri.parse("google.navigation:q=$lat,$lon") },
    ),
    MapTarget(
        name = "Yandex Maps",
        packageName = "ru.yandex.yandexmaps",
        pinUri = { lat, lon, _ -> Uri.parse("yandexmaps://maps.yandex.ru/?pt=$lon,$lat&z=16&l=map") },
        routeUri = { lat, lon -> Uri.parse("yandexmaps://maps.yandex.ru/?rtext=~$lat,$lon&rtt=auto") },
    ),
    MapTarget(
        name = "Yandex Navigator",
        packageName = "ru.yandex.yandexnavi",
        pinUri = { lat, lon, _ -> Uri.parse("yandexnavi://show_point_on_map?lat=$lat&lon=$lon&zoom=16") },
        routeUri = { lat, lon -> Uri.parse("yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon") },
    ),
    MapTarget(
        name = "2GIS",
        packageName = "ru.dublgis.dgismobile",
        pinUri = { lat, lon, _ -> Uri.parse("dgis://2gis.ru/geo/$lon,$lat") },
        routeUri = { lat, lon -> Uri.parse("dgis://2gis.ru/routeSearch/to/$lon,$lat/go") },
    ),
    MapTarget(
        name = "Waze",
        packageName = "com.waze",
        pinUri = { lat, lon, _ -> Uri.parse("waze://?ll=$lat,$lon") },
        routeUri = { lat, lon -> Uri.parse("waze://?ll=$lat,$lon&navigate=yes") },
    ),
    MapTarget(
        name = "HERE WeGo",
        packageName = "com.here.app.maps",
        pinUri = { lat, lon, _ -> Uri.parse("https://share.here.com/l/$lat,$lon") },
        routeUri = { lat, lon -> Uri.parse("here-route://?mylocation&destination=$lat,$lon") },
    ),
)

/** The URI every map app handles. */
private fun geoPinUri(lat: Double, lon: Double, address: String): Uri =
    Uri.parse("geo:$lat,$lon?q=${Uri.encode(address.ifBlank { "$lat,$lon" })}")

/**
 * Installed map apps: the curated packages, then every other app resolving a coordinate intent.
 * API 30+ hides both probes unless the manifest `queries` entries declare them.
 */
internal fun installedMapTargets(context: Context): List<MapTarget> {
    val manager = context.packageManager
    val curated = mapTargets.filter { hasLauncherActivity(manager, it.packageName) }
    val curatedPackages = curated.mapTo(mutableSetOf()) { it.packageName }
    val discovered = geoIntentApps(manager)
        .filterNot { it.packageName in curatedPackages }
        .map { MapTarget(it.label, it.packageName, ::geoPinUri, routeUri = null) }
    return curated + discovered
}

private fun hasLauncherActivity(manager: PackageManager, packageName: String): Boolean =
    runCatching { manager.getLaunchIntentForPackage(packageName) }.getOrNull() != null

private data class GeoIntentApp(val label: String, val packageName: String)

private fun geoIntentApps(manager: PackageManager): List<GeoIntentApp> {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0"))
    val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        manager.queryIntentActivities(probe, 0)
    }
    return resolved
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(manager).toString().trim()
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            GeoIntentApp(label, packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

internal fun openMap(
    context: Context,
    target: MapTarget,
    latitude: Double,
    longitude: Double,
    address: String,
    route: Boolean,
) {
    val routeUri = target.routeUri
    val uri = if (route && routeUri != null) {
        routeUri(latitude, longitude)
    } else {
        target.pinUri(latitude, longitude, address)
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(target.packageName)
    runCatching { context.startActivity(intent) }
}

private val MapRowIconSize = 28.dp

/**
 * Map window: a row per installed map app. The row opens the pin, and a row whose app can route
 * carries a trailing route button. Dismisses through a row, the scrim, or Cancel.
 */
@Composable
internal fun LocationActionsSheet(
    coordinates: String,
    latitude: Double,
    longitude: Double,
    address: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val targets = remember(context) { installedMapTargets(context) }
    // The app-wide Shapes.small is 12.dp, so the 16.dp press radius is pinned here.
    val cancelShapes = ButtonDefaults.shapes(
        shape = CircleShape,
        pressedShape = RoundedCornerShape(ExpressiveDefaults.PressRadius),
    )
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = coordinates,
                style = ExpressiveDefaults.titleSemiBold(),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (address.isNotBlank() && address != coordinates) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))
            if (targets.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.dialog_location_no_maps),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.dialog_location_open_maps),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    targets.forEach { target ->
                        MapAppRow(
                            target = target,
                            onOpen = { route ->
                                openMap(context, target, latitude, longitude, address, route)
                                onDismiss()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onDismiss,
                shapes = cancelShapes,
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

@Composable
private fun MapAppRow(target: MapTarget, onOpen: (route: Boolean) -> Unit) {
    Surface(
        onClick = { onOpen(false) },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapAppIcon(target)
            Spacer(Modifier.width(14.dp))
            Text(
                text = target.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (target.routeUri != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = { onOpen(true) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Directions,
                        contentDescription = stringResource(R.string.dialog_location_route),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** The app's own launcher icon; the map glyph stands in until it loads. */
@Composable
private fun MapAppIcon(target: MapTarget) {
    val context = LocalContext.current
    val icon by produceState<Drawable?>(initialValue = null, target.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(target.packageName) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier.size(MapRowIconSize),
        contentAlignment = Alignment.Center,
    ) {
        val drawable = icon
        if (drawable == null) {
            Icon(
                imageVector = Icons.Outlined.Map,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(drawable).crossfade(false).build(),
                contentDescription = null,
                modifier = Modifier.size(MapRowIconSize),
            )
        }
    }
}
