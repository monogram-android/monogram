package org.monogram.presentation.features.profile.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.maplibre.compose.MapView
import com.maplibre.compose.camera.CameraState
import com.maplibre.compose.camera.MapViewCamera
import com.maplibre.compose.rememberSaveableMapViewCamera
import com.maplibre.compose.symbols.Symbol
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.monogram.presentation.R
import org.monogram.presentation.features.profile.ProfileComponent

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/bright"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationViewer(
    location: ProfileComponent.LocationData,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var showMapsSelection by remember { mutableStateOf(false) }
    var isNavigationMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val camera = rememberSaveableMapViewCamera(
        MapViewCamera(
            CameraState.Centered(
                location.latitude,
                location.longitude,
            ),
        )
    )
    val mapOptions = remember {
        MapLibreMapOptions.createFromAttributes(context, null).apply {
            scrollGesturesEnabled(true)
            zoomGesturesEnabled(true)
            tiltGesturesEnabled(false)
            rotateGesturesEnabled(false)
            doubleTapGesturesEnabled(true)
            textureMode(true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.location_label),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                MapView(
                    modifier = Modifier
                        .fillMaxWidth(),
                    camera = camera,
                    styleUrl = MAP_STYLE,
                    mapOptions = mapOptions
                ) {
                    Symbol(
                        center = LatLng(location.latitude, location.longitude),
                        imageId = R.drawable.ic_map_marker,
                        size = 2f
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = location.address,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${location.latitude}, ${location.longitude}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        isNavigationMode = false
                        showMapsSelection = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Map, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_open_maps))
                }

                FilledTonalButton(
                    onClick = {
                        isNavigationMode = true
                        showMapsSelection = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Directions, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_directions))
                }
            }
        }
    }

    if (showMapsSelection) {
        MapsSelectionDialog(
            location = location,
            isNavigation = isNavigationMode,
            onDismiss = { showMapsSelection = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapsSelectionDialog(
    location: ProfileComponent.LocationData,
    isNavigation: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val mapsApps =
        listOf(
            MapApp(
                name = "Google Maps",
                packageName = "com.google.android.apps.maps",
                uriBuilder = { lat, lon, addr -> "geo:$lat,$lon?q=${Uri.encode(addr)}".toUri() },
                navigationUriBuilder = { lat, lon -> "google.navigation:q=$lat,$lon".toUri() }
            ),
            MapApp(
                name = "Yandex Maps",
                packageName = "ru.yandex.yandexmaps",
                uriBuilder = { lat, lon, _ -> "yandexmaps://maps.yandex.ru/?pt=$lon,$lat&z=16&l=map".toUri() },
                navigationUriBuilder = { lat, lon -> "yandexmaps://maps.yandex.ru/?rtext=~$lat,$lon&rtt=auto".toUri() }
            ),
            MapApp(
                name = "Yandex Navigator",
                packageName = "ru.yandex.yandexnavi",
                uriBuilder = { lat, lon, _ -> "yandexnavi://show_point_on_map?lat=$lat&lon=$lon&zoom=16".toUri() },
                navigationUriBuilder = { lat, lon -> "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon".toUri() }
            ),
            MapApp(
                name = "2GIS",
                packageName = "ru.dublgis.dgismobile",
                uriBuilder = { lat, lon, _ -> "dgis://2gis.ru/geo/$lon,$lat".toUri() },
                navigationUriBuilder = { lat, lon -> "dgis://2gis.ru/routeSearch/to/$lon,$lat/go".toUri() }
            ),
            MapApp(
                name = "Waze",
                packageName = "com.waze",
                uriBuilder = { lat, lon, _ -> "waze://?ll=$lat,$lon".toUri() },
                navigationUriBuilder = { lat, lon -> "waze://?ll=$lat,$lon&navigate=yes".toUri() }
            ),
            MapApp(
                name = "HERE WeGo",
                packageName = "com.here.app.maps",
                uriBuilder = { lat, lon, _ -> "https://share.here.com/l/$lat,$lon".toUri() },
                navigationUriBuilder = { lat, lon -> "here-route://?mylocation&destination=$lat,$lon".toUri() }
            ),
            MapApp(
                name = "Sygic",
                packageName = "com.sygic.aura",
                uriBuilder = { lat, lon, _ -> "com.sygic.aura://coordinate|$lon|$lat|show".toUri() },
                navigationUriBuilder = { lat, lon -> "com.sygic.aura://coordinate|$lon|$lat|drive".toUri() }
            ),
            MapApp(
                name = "OsmAnd",
                packageName = "net.osmand",
                uriBuilder = { lat, lon, _ -> "osmand.api://show_point?lat=$lat&lon=$lon".toUri() },
                navigationUriBuilder = { lat, lon -> "osmand.api://navigate?lat=$lat&lon=$lon&profile=car".toUri() }
            ),
            MapApp(
                name = "Organic Maps",
                packageName = "app.organicmaps",
                uriBuilder = { lat, lon, addr -> "geo:$lat,$lon?q=${Uri.encode(addr)}".toUri() },
                navigationUriBuilder = { lat, lon -> "google.navigation:q=$lat,$lon".toUri() }
            ),
            MapApp(
                name = "Maps.me",
                packageName = "com.mapswithme.maps.pro",
                uriBuilder = { lat, lon, addr -> "geo:$lat,$lon?q=${Uri.encode(addr)}".toUri() },
                navigationUriBuilder = { lat, lon -> "mapsme://route?sll=0,0&dll=$lat,$lon&type=vehicle".toUri() }
            ),
            MapApp(
                name = "Citymapper",
                packageName = "com.citymapper.app.release",
                uriBuilder = { lat, lon, addr -> "citymapper://directions?endcoord=$lat,$lon&endname=${Uri.encode(addr)}".toUri() },
                navigationUriBuilder = { lat, lon -> "citymapper://directions?endcoord=$lat,$lon".toUri() }
            ),
            MapApp(
                name = "Petal Maps",
                packageName = "com.huawei.maps.app",
                uriBuilder = { lat, lon, _ -> "petalmaps://geo?center=$lat,$lon&zoom=16".toUri() },
                navigationUriBuilder = { lat, lon -> "petalmaps://navigation?daddr=$lat,$lon&type=drive".toUri() }
            ),
            MapApp(
                name = "KakaoMap",
                packageName = "net.daum.android.map",
                uriBuilder = { lat, lon, _ -> "kakaomap://look?p=$lat,$lon".toUri() },
                navigationUriBuilder = { lat, lon -> "kakaomap://route?ep=$lat,$lon&by=CAR".toUri() }
            ),
            MapApp(
                name = "Naver Map",
                packageName = "com.nhn.android.nmap",
                uriBuilder = { lat, lon, addr -> "nmap://place?lat=$lat&lng=$lon&name=${Uri.encode(addr)}&appname=com.example.app".toUri() },
                navigationUriBuilder = { lat, lon -> "nmap://route/car?dlat=$lat&dlng=$lon&appname=com.example.app".toUri() }
            ),
            MapApp(
                name = "Baidu Maps",
                packageName = "com.baidu.BaiduMap",
                uriBuilder = { lat, lon, addr -> "baidumap://map/marker?location=$lat,$lon&title=${Uri.encode(addr)}&src=andr.baidu.openAPIdemo".toUri() },
                navigationUriBuilder = { lat, lon -> "baidumap://map/direction?destination=$lat,$lon&mode=driving&src=andr.baidu.openAPIdemo".toUri() }
            ),
            MapApp(
                name = "Gaode / AutoNavi",
                packageName = "com.autonavi.minimap",
                uriBuilder = { lat, lon, addr -> "androidamap://viewMap?lat=$lat&lon=$lon&poiname=${Uri.encode(addr)}&sourceApplication=appname".toUri() },
                navigationUriBuilder = { lat, lon -> "androidamap://navi?lat=$lat&lon=$lon&dev=0&style=2&sourceApplication=appname".toUri() }
            )
        )

    val installedApps = remember(mapsApps) {
        mapsApps.filter { app ->
            context.packageManager.getLaunchIntentForPackage(app.packageName) != null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(if (isNavigation) R.string.navigate_with else R.string.open_with),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column {
                    installedApps.forEachIndexed { index, app ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            modifier = Modifier.clickable {
                                val uri = if (isNavigation) {
                                    app.navigationUriBuilder(location.latitude, location.longitude)
                                } else {
                                    app.uriBuilder(location.latitude, location.longitude, location.address)
                                }
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                intent.setPackage(app.packageName)
                                context.startActivity(intent)
                                onDismiss()
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        if (index < installedApps.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    if (installedApps.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.browser_other), style = MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.clickable {
                            val uri = if (isNavigation) {
                                "google.navigation:q=${location.latitude},${location.longitude}".toUri()
                            } else {
                                "geo:${location.latitude},${location.longitude}?q=${Uri.encode(location.address)}".toUri()
                            }
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                            onDismiss()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

private data class MapApp(
    val name: String,
    val packageName: String,
    val uriBuilder: (Double, Double, String) -> Uri,
    val navigationUriBuilder: (Double, Double) -> Uri
)
