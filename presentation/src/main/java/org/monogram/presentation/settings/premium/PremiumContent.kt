package org.monogram.presentation.settings.premium

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.PremiumPaymentOptionModel
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PremiumContent(component: PremiumComponent) {
    val state by component.state.subscribeAsState()
    val context = LocalContext.current
    val telegramLinkRepository: TelegramLinkRepository = koinInject()
    val scope = rememberCoroutineScope()

    fun openPremiumBot() {
        scope.launch {
            runCatching {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(telegramLinkRepository.buildUrl("PremiumBot"))
                )
                context.startActivity(intent)
                component.onSubscribeClicked()
            }
        }
    }

    val firstPaymentOption = state.paymentOptions.firstOrNull()

    Scaffold(
        modifier = Modifier.semantics { contentDescription = "PremiumContent" },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.premium_title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = component::onBackClicked,
                        shapes = ExpressiveDefaults.iconButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.premium_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (!state.isPremium) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp
                ) {
                    Button(
                        onClick = ::openPremiumBot,
                        shapes = ExpressiveDefaults.largeButtonShapes(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(ButtonDefaults.MediumContainerHeight),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFAF52DE)
                        )
                    ) {
                        Text(
                            text = firstPaymentOption?.let { option ->
                                stringResource(
                                    R.string.premium_subscribe_button_format,
                                    formatPremiumAmount(option)
                                )
                            } ?: stringResource(R.string.premium_subscribe_button_fallback),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    bottom = padding.calculateBottomPadding() + 24.dp
                )
            ) {
                item {
                    PremiumHero(
                        isPremium = state.isPremium,
                        statusText = state.statusText
                    )
                }

                if (state.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFFAF52DE))
                        }
                    }
                }

                if (!state.isPremium && state.paymentOptions.isNotEmpty()) {
                    item {
                        PremiumSectionTitle(R.string.premium_plans_title)
                    }
                    itemsIndexed(
                        items = state.paymentOptions,
                        key = { index, option -> "${option.storeProductId}-$index" }
                    ) { _, option ->
                        PremiumPlanItem(option)
                    }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }

                if (state.features.isNotEmpty()) {
                    item {
                        PremiumSectionTitle(R.string.premium_features_section)
                    }
                    itemsIndexed(
                        items = state.features,
                        key = { index, feature -> "${feature.title}-$index" }
                    ) { index, feature ->
                        PremiumFeatureItem(
                            feature = feature,
                            showDivider = index < state.features.lastIndex
                        )
                    }
                }

                if (state.limits.isNotEmpty()) {
                    item {
                        PremiumSectionTitle(R.string.premium_limits_section)
                    }
                    item {
                        PremiumLimitsCard(state.limits)
                    }
                }

                if (state.isPremium) {
                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            SettingsSwitchTile(
                                icon = Icons.Rounded.Campaign,
                                title = stringResource(R.string.premium_show_sponsored_messages_title),
                                subtitle = stringResource(R.string.premium_show_sponsored_messages_subtitle),
                                checked = state.showSponsoredMessagesForPremium,
                                iconColor = Color(0xFF00BFA5),
                                position = ItemPosition.STANDALONE,
                                onCheckedChange = component::onShowSponsoredMessagesChanged
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumHero(
    isPremium: Boolean,
    statusText: String?
) {
    val subtitle = if (isPremium) {
        statusText ?: stringResource(R.string.premium_status_active)
    } else {
        stringResource(R.string.premium_unlock_features)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(76.dp)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.premium_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun PremiumSectionTitle(resourceId: Int) {
    Text(
        text = stringResource(resourceId),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PremiumPlanItem(option: PremiumPaymentOptionModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = Color(0xFFAF52DE).copy(alpha = 0.14f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = Color(0xFFAF52DE),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatPremiumAmount(option),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.premium_plan_months,
                        option.monthCount.coerceAtLeast(1),
                        option.monthCount.coerceAtLeast(1)
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (option.discountPercentage > 0) {
                    Text(
                        text = stringResource(
                            R.string.premium_plan_discount,
                            option.discountPercentage
                        ),
                        color = Color(0xFF168A4A),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (option.isCurrent) {
                    Text(
                        text = stringResource(R.string.premium_plan_current),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else if (option.isUpgrade) {
                    Text(
                        text = stringResource(R.string.premium_plan_upgrade),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumFeatureItem(
    feature: PremiumComponent.PremiumFeature,
    showDivider: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = Color(feature.color).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getIconForName(feature.icon),
                    contentDescription = null,
                    tint = Color(feature.color),
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = feature.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = feature.description,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
        }
    }
}

@Composable
private fun PremiumLimitsCard(limits: List<PremiumComponent.PremiumLimit>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            limits.forEachIndexed { index, limit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = limit.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        limit.subtitle?.let { subtitle ->
                            Text(
                                text = subtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(
                            R.string.premium_limit_value_format,
                            limit.defaultValue,
                            limit.premiumValue
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (index < limits.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

fun getIconForName(name: String) = when (name) {
    "mic" -> Icons.Rounded.Mic
    "download" -> Icons.Rounded.Download
    "translate" -> Icons.Rounded.Translate
    "face" -> Icons.Rounded.Face
    "folder" -> Icons.Rounded.Folder
    "block" -> Icons.Rounded.Block
    "heart", "favorite" -> Icons.Rounded.Favorite
    "verified" -> Icons.Rounded.Verified
    "settings" -> Icons.Rounded.Settings
    else -> Icons.Rounded.Star
}

private fun formatPremiumAmount(option: PremiumPaymentOptionModel): String {
    val currency = runCatching { Currency.getInstance(option.currency) }.getOrNull()
        ?: return "${option.amount} ${option.currency}"
    val fractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
    val value = BigDecimal.valueOf(option.amount).movePointLeft(fractionDigits)
    return NumberFormat.getCurrencyInstance().apply {
        this.currency = currency
    }.format(value)
}
