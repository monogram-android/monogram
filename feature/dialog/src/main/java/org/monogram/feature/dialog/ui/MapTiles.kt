package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import org.monogram.core.models.GeoPlace
import org.monogram.feature.dialog.R
import org.monogram.network.http.MapTileStore

/**
 * A small raster map of [place], drawn from OpenStreetMap tiles by [MapTileStore].
 *
 * [mapShape] and [marker] let the picker reuse it as a 16:10 card with a larger pin.
 */
@Composable
internal fun LocationMap(
    place: GeoPlace,
    mapTiles: MapTileStore?,
    modifier: Modifier = Modifier,
    mapWidth: Dp = CardWidthDp,
    mapHeight: Dp = CardHeightDp,
    mapShape: Shape = RoundedCornerShape(12.dp),
    marker: (@Composable BoxScope.() -> Unit)? = null,
) {
    val zoom = if (place.live) LiveZoom else PlaceZoom
    val density = LocalDensity.current
    val tilePx = with(density) { TileRenderSize.toPx() }
    val cardWidthPx = with(density) { mapWidth.toPx() }
    val cardHeightPx = with(density) { mapHeight.toPx() }
    val grid = remember(place.latitude, place.longitude, zoom, tilePx, cardWidthPx, cardHeightPx) {
        tileGrid(place, zoom, tilePx, cardWidthPx, cardHeightPx)
    }
    val files = remember { mutableStateMapOf<Int, java.io.File>() }

    LaunchedEffect(mapTiles, grid?.signature) {
        val store = mapTiles ?: return@LaunchedEffect
        val grid = grid ?: return@LaunchedEffect
        grid.tiles.forEach { tile ->
            store.cached(tile.zoom, tile.x, tile.y)?.let { files[tile.key] = it }
        }
        coroutineScope {
            grid.tiles.forEach { tile ->
                if (files[tile.key] != null) return@forEach
                launch {
                    store.tile(tile.zoom, tile.x, tile.y)?.let { files[tile.key] = it }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .width(mapWidth)
            .height(mapHeight)
            .clip(mapShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (grid != null) {
            val offset = grid.offset
            grid.tiles.forEach { tile ->
                val file = files[tile.key]
                if (file != null) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (tile.column * grid.tilePx).toInt() + offset.x,
                                    (tile.row * grid.tilePx).toInt() + offset.y,
                                )
                            }
                            .size(TileRenderSize),
                    )
                }
            }
        }
        if (marker != null) {
            marker()
        } else {
            Icon(
                imageVector = Icons.Outlined.LocationOn,
                contentDescription = stringResource(R.string.dialog_location_marker),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            shape = RoundedCornerShape(topStart = 6.dp),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Text(
                text = stringResource(R.string.dialog_map_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

private data class MapTile(val zoom: Int, val x: Int, val y: Int, val row: Int, val column: Int) {
    val key: Int get() = (zoom * 31 + x) * 31 + y
}

private class TileGrid(
    val tiles: List<MapTile>,
    val offset: IntOffset,
    val tilePx: Float,
    val signature: Int,
)

private val TileRenderSize = 128.dp
private const val PlaceZoom = 16
private const val LiveZoom = 15

private fun tileGrid(place: GeoPlace, zoom: Int, tilePx: Float, cardWidthPx: Float, cardHeightPx: Float): TileGrid? {
    val scale = 1 shl zoom
    val x = (place.longitude + 180.0) / 360.0 * scale
    val latitudeRadians = Math.toRadians(place.latitude.coerceIn(-85.05112878, 85.05112878))
    val y = (1.0 - ln(tan(latitudeRadians) + 1.0 / cos(latitudeRadians)) / PI) / 2.0 * scale
    if (!x.isFinite() || !y.isFinite() || tilePx <= 0f) return null
    val halfW = cardWidthPx / 2f / tilePx
    val halfH = cardHeightPx / 2f / tilePx
    val originX = floor(x - halfW).toInt() - 1
    val originY = floor(y - halfH).toInt() - 1
    val lastX = ceil(x + halfW).toInt() + 1
    val lastY = ceil(y + halfH).toInt() + 1
    val tiles = buildList {
        var row = 0
        for (tileY in originY..lastY) {
            if (tileY in 0 until scale) {
                var column = 0
                for (tileX in originX..lastX) {
                    val wrappedX = ((tileX % scale) + scale) % scale
                    add(MapTile(zoom = zoom, x = wrappedX, y = tileY, row = row, column = column))
                    column++
                }
            }
            row++
        }
    }
    if (tiles.isEmpty()) return null
    val pointX = (x - originX).toFloat() * tilePx
    val pointY = (y - originY).toFloat() * tilePx
    return TileGrid(
        tiles = tiles,
        offset = IntOffset(
            x = (cardWidthPx / 2f - pointX).toInt(),
            y = (cardHeightPx / 2f - pointY).toInt(),
        ),
        tilePx = tilePx,
        signature = ((zoom * 31 + originX) * 31 + originY) * 31 + tiles.size,
    )
}

internal val CardWidthDp = 260.dp
internal val CardHeightDp = 148.dp
