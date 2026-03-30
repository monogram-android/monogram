package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maplibre.compose.MapView
import com.maplibre.compose.camera.CameraState.Centered
import com.maplibre.compose.camera.MapViewCamera
import com.maplibre.compose.rememberSaveableMapViewCamera
import com.maplibre.compose.symbols.Symbol
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.features.profile.ProfileComponent
import org.monogram.presentation.features.profile.components.LocationViewer

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/bright"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocationMessageBubble(
    content: MessageContent.Location,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float,
    isGroup: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReplyClick: (MessageModel) -> Unit,
    onReactionClick: (String) -> Unit,
    toProfile: (Long) -> Unit = {},
    showReactions: Boolean = true
) {
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp
    var showLocationViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val bubbleShape = RoundedCornerShape(
        topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        bottomStart = if (!isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius,
        bottomEnd = if (isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius
    )

    val backgroundColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)
    val camera = rememberSaveableMapViewCamera(
        MapViewCamera(
            Centered(
                content.latitude,
                content.longitude,
            ),
        )
    )
    val mapOptions = remember {
        MapLibreMapOptions.createFromAttributes(context, null).apply {
            scrollGesturesEnabled(false)
            zoomGesturesEnabled(false)
            tiltGesturesEnabled(false)
            rotateGesturesEnabled(false)
            doubleTapGesturesEnabled(false)
            textureMode(true)
        }
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .widthIn(min = 280.dp, max = 360.dp),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp,
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    MessageSenderName(msg, toProfile = toProfile)
                }

                msg.forwardInfo?.let { forward ->
                    ForwardContent(forward, isOutgoing, onForwardClick = toProfile)
                }
                msg.replyToMsg?.let { reply ->
                    ReplyContent(
                        replyToMsg = reply,
                        isOutgoing = isOutgoing,
                        onClick = { onReplyClick(reply) }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(bubbleRadius.dp / 2f))
                ) {
                    MapView(
                        modifier = Modifier
                            .fillMaxWidth(),
                        camera = camera,
                        styleUrl = MAP_STYLE,
                        mapOptions = mapOptions
                    ) {
                        Symbol(
                            center = LatLng(content.latitude, content.longitude),
                            imageId = R.drawable.ic_map_marker,
                            size = 2f
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .clickable { showLocationViewer = true }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLocationViewer = true }
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFEA4335), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Location",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = fontSize.sp,
                                letterSpacing = letterSpacing.sp
                            ),
                            color = contentColor
                        )
                        Text(
                            text = String.format("%.4f, %.4f", content.latitude, content.longitude),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    }
                }

                Box(modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)) {
                    MessageMetadata(msg, isOutgoing, timeColor)
                }
            }
        }

        if (showReactions) {
            MessageReactionsView(
                reactions = msg.reactions,
                onReactionClick = onReactionClick,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    if (showLocationViewer) {
        LocationViewer(
            location = ProfileComponent.LocationData(
                latitude = content.latitude,
                longitude = content.longitude,
                address = "Location"
            ),
            onDismiss = { showLocationViewer = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VenueMessageBubble(
    content: MessageContent.Venue,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float,
    isGroup: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReplyClick: (MessageModel) -> Unit,
    onReactionClick: (String) -> Unit,
    toProfile: (Long) -> Unit = {},
    showReactions: Boolean = true
) {
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp
    var showLocationViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val bubbleShape = RoundedCornerShape(
        topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        bottomStart = if (!isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius,
        bottomEnd = if (isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius
    )

    val backgroundColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)
    val camera = rememberSaveableMapViewCamera(
        MapViewCamera(
            Centered(
                content.latitude,
                content.longitude,
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
    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .widthIn(min = 280.dp, max = 360.dp),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp,
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    MessageSenderName(msg, toProfile = toProfile)
                }

                msg.forwardInfo?.let { forward ->
                    ForwardContent(forward, isOutgoing, onForwardClick = toProfile)
                }
                msg.replyToMsg?.let { reply ->
                    ReplyContent(
                        replyToMsg = reply,
                        isOutgoing = isOutgoing,
                        onClick = { onReplyClick(reply) }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(bubbleRadius.dp / 2f))
                ) {
                    MapView(
                        modifier = Modifier
                            .fillMaxWidth(),
                        camera = camera,
                        styleUrl = MAP_STYLE,
                        mapOptions = mapOptions
                    ) {
                        Symbol(
                            center = LatLng(content.latitude, content.longitude),
                            imageId = R.drawable.ic_map_marker,
                            size = 2f
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .clickable { showLocationViewer = true }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLocationViewer = true }
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF4285F4), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = content.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = fontSize.sp,
                                letterSpacing = letterSpacing.sp
                            ),
                            color = contentColor,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = content.address,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Box(modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)) {
                    MessageMetadata(msg, isOutgoing, timeColor)
                }
            }
        }

        if (showReactions) {
            MessageReactionsView(
                reactions = msg.reactions,
                onReactionClick = onReactionClick,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    if (showLocationViewer) {
        LocationViewer(
            location = ProfileComponent.LocationData(
                latitude = content.latitude,
                longitude = content.longitude,
                address = content.address
            ),
            onDismiss = { showLocationViewer = false }
        )
    }
}
