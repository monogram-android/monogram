package org.monogram.presentation.settings.premium

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.CollapsingToolbarScaffold
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.core.ui.TelegramStarInteractive
import org.monogram.presentation.core.ui.rememberCollapsingToolbarScaffoldState
import org.monogram.presentation.core.util.ScrollStrategy

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PremiumContent(component: PremiumComponent) {
    val state by component.state.subscribeAsState()
    val context = LocalContext.current
    val states = rememberCollapsingToolbarScaffoldState()
    val currentRadius = 28.dp * states.toolbarState.progress
    val collapsedColor = MaterialTheme.colorScheme.surface
    val expandedColor = MaterialTheme.colorScheme.primaryContainer
    val dynamicContainerColor = lerp(
        start = collapsedColor,
        stop = expandedColor,
        fraction = states.toolbarState.progress
    )

    Scaffold(
        modifier = Modifier.semantics { contentDescription = "PremiumContent" },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.premium_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = component::onBackClicked,
                        shapes = ExpressiveDefaults.iconButtonShapes()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.premium_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = dynamicContainerColor,
                    scrolledContainerColor = dynamicContainerColor
                )
            )
        },
        bottomBar = {
            if (!state.isPremium) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/PremiumBot"))
                                context.startActivity(intent)
                                component.onSubscribeClicked()
                            },
                            shapes = ExpressiveDefaults.largeButtonShapes(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .height(ButtonDefaults.MediumContainerHeight),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFAF52DE)
                            )
                        ) {
                            Text(
                                stringResource(R.string.premium_subscribe_button, "$4.99"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        CollapsingToolbarScaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(dynamicContainerColor)
                .padding(top = padding.calculateTopPadding()),
            state = states,
            scrollStrategy = ScrollStrategy.ExitUntilCollapsed,
            toolbar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.dp)
                        .pin()
                        .background(dynamicContainerColor)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .road(Alignment.Center, Alignment.BottomCenter)
                        .padding(top = 16.dp, bottom = 48.dp)
                        .alpha(states.toolbarState.progress),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)) {
                        TelegramStarInteractive(
                            resId = R.raw.star,
                            alpha = states.toolbarState.progress
                        )
                    }
                }
            }
        ) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(topStart = currentRadius, topEnd = currentRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 24.dp, bottom = padding.calculateBottomPadding())
                ) {
                    item {
                        PremiumStatusCard(
                            isPremium = state.isPremium,
                            statusText = state.statusText,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/PremiumBot"))
                                context.startActivity(intent)
                            }
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    items(state.features) { feature ->
                        PremiumFeatureItem(feature)
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumStatusCard(
    isPremium: Boolean,
    statusText: String?,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                if (isPremium && statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                } else if (!isPremium) {
                    Text(
                        text = stringResource(R.string.premium_unlock_features),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        }
    }
}

@Composable
fun PremiumFeatureItem(feature: PremiumComponent.PremiumFeature) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(feature.color).copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getIconForName(feature.icon),
                contentDescription = null,
                tint = Color(feature.color),
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = feature.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = feature.description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun getIconForName(name: String): ImageVector {
    return when (name) {
        "star" -> Icons.Rounded.Star
        "mic" -> Icons.Rounded.Mic
        "download" -> Icons.Rounded.Download
        "translate" -> Icons.Rounded.Translate
        "face" -> Icons.Rounded.Face
        "folder" -> Icons.Rounded.Folder
        "block" -> Icons.Rounded.Block
        "heart" -> Icons.Rounded.Favorite
        "verified" -> Icons.Rounded.Verified
        "settings" -> Icons.Rounded.Settings
        else -> Icons.Rounded.Star
    }
}

