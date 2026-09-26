package org.monogram.feature.profile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import org.monogram.feature.profile.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProfileTopBar(
    atTop: Boolean,
    title: String?,
    onBack: () -> Unit,
) {
    val enterFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val enterScale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val exitFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val exitScale = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    TopAppBar(
        windowInsets = WindowInsets.statusBars,
        title = {
            AnimatedContent(
                targetState = !atTop && !title.isNullOrBlank(),
                transitionSpec = {
                    (fadeIn(enterFade) + scaleIn(initialScale = 0.98f, animationSpec = enterScale))
                        .togetherWith(
                            fadeOut(exitFade) + scaleOut(targetScale = 0.98f, animationSpec = exitScale),
                        )
                },
                label = "profileBarTitle",
            ) { showTitle ->
                if (showTitle) {
                    Text(
                        text = title.orEmpty(),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.profile_back),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
