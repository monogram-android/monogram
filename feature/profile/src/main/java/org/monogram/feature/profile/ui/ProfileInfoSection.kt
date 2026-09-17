package org.monogram.feature.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.common.CountryManager
import org.monogram.core.models.Profile
import org.monogram.core.ui.components.SectionHeader
import org.monogram.feature.profile.R
import org.monogram.feature.profile.profilePhoneDetailLabel

private data class ProfileInfoRowData(
    val value: String,
    val label: String,
    val detail: String? = null,
    val icon: ImageVector,
    val color: Color,
)

@Composable
internal fun ProfileInfoSection(
    profile: Profile,
    onCopy: (String) -> Unit,
) {
    val copyLabel = stringResource(R.string.profile_copy)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val rows = buildList {
        profile.username?.takeIf { it.isNotBlank() }?.let {
            add(
                ProfileInfoRowData(
                    value = stringResource(R.string.profile_username, it),
                    label = stringResource(R.string.profile_username_label),
                    icon = Icons.Outlined.AlternateEmail,
                    color = primary,
                ),
            )
        }
        profile.phone?.takeIf { it.isNotBlank() }?.let { phone ->
            add(
                ProfileInfoRowData(
                    value = formatProfilePhone(phone),
                    label = stringResource(R.string.profile_phone_label),
                    detail = profilePhoneDetailLabel(phone),
                    icon = Icons.Outlined.Phone,
                    color = secondary,
                ),
            )
        }
        profile.about?.takeIf { it.isNotBlank() }?.let {
            add(
                ProfileInfoRowData(
                    value = it,
                    label = stringResource(R.string.profile_about_label),
                    icon = Icons.AutoMirrored.Outlined.Notes,
                    color = tertiary,
                ),
            )
        }
        add(
            ProfileInfoRowData(
                value = profile.id.value.toString(),
                label = stringResource(R.string.profile_id_label),
                icon = Icons.Outlined.Tag,
                color = primary,
            ),
        )
    }
    if (rows.isEmpty()) return
    SectionHeader(
        stringResource(R.string.profile_info),
        modifier = Modifier.fillMaxWidth(),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(start = 64.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    )
                }
                ProfileInfoRow(
                    row = row,
                    copyLabel = copyLabel,
                    onCopy = { onCopy(row.value) },
                )
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(
    row: ProfileInfoRowData,
    copyLabel: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = copyLabel, onClick = onCopy)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(color = row.color.copy(alpha = 0.15f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = row.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = row.color,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.value, style = MaterialTheme.typography.bodyLarge)
            val secondary = row.detail ?: row.label
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun formatProfilePhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (digits.isEmpty()) return raw
    val country = runCatching { CountryManager.countryForPhone(digits) }.getOrNull()
    if (country != null) {
        val masked = applyCountryMask(digits.removePrefix(country.code), country.mask)
        if (masked != null) return "+${country.code} $masked"
    }
    val international = runCatching {
        CountryManager.formatPhoneNumber(raw)
    }.getOrNull()
    if (!international.isNullOrBlank()) return international
    return if (raw.startsWith("+")) raw else "+$digits"
}

private fun applyCountryMask(digits: String, mask: String?): String? {
    if (mask.isNullOrBlank() || digits.isEmpty()) return null
    val places = mask.count { it == 'X' || it == 'x' }
    if (places == 0 || digits.length != places) return null
    val out = StringBuilder()
    var index = 0
    for (char in mask) {
        if (char == 'X' || char == 'x') {
            if (index >= digits.length) return null
            out.append(digits[index++])
        } else {
            out.append(char)
        }
    }
    return out.toString()
}
