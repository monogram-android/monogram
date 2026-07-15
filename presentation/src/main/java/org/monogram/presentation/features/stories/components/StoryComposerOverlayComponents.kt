package org.monogram.presentation.features.stories.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import org.monogram.domain.models.stories.StoryComposerMediaItemModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTextField
import org.monogram.presentation.core.ui.SettingsTile
import org.monogram.presentation.features.camera.CameraScreen
import org.monogram.presentation.features.chats.conversation.editor.photo.PhotoEditorScreen
import org.monogram.presentation.features.chats.conversation.editor.video.VideoEditorScreen
import org.monogram.presentation.features.chats.conversation.ui.inputbar.copyUriToTempPath
import org.monogram.presentation.features.chats.conversation.ui.inputbar.declaredPermissions
import org.monogram.presentation.features.chats.conversation.ui.inputbar.hasAllPermissions
import org.monogram.presentation.features.gallery.GalleryScreen
import org.monogram.presentation.features.stories.STORY_MEDIA_ASPECT_RATIO
import org.monogram.presentation.features.stories.StoriesHostComponent
import org.monogram.presentation.features.stories.StoryAudienceFilterMode
import org.monogram.presentation.features.stories.StoryCapabilityPresentation
import org.monogram.presentation.features.stories.StoryComposerMode
import org.monogram.presentation.features.stories.StoryErrorBanner
import org.monogram.presentation.features.stories.StoryPrivacyUi
import org.monogram.presentation.features.stories.canPublishStory
import org.monogram.presentation.features.stories.formatStoryDurationLabel
import org.monogram.presentation.features.stories.inferUriMediaType
import org.monogram.presentation.features.stories.toCapabilityPresentation
import java.io.File

private enum class StoryComposerStage {
    COMPOSE,
    PREVIEW,
    PHOTO_EDITOR,
    VIDEO_EDITOR
}

private data class StoryComposerActionModel(
    val icon: ImageVector,
    val title: Int,
    val subtitle: Int,
    val iconColor: Color,
    val enabled: Boolean,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StoryComposerOverlayComponent(
    state: StoriesHostComponent.State,
    component: StoriesHostComponent
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val capability = state.postCapability.toCapabilityPresentation()
    val isEditMode = state.composerMode == StoryComposerMode.EDIT
    val hasMedia = state.composerDraft.isValid
    var stage by rememberSaveable { mutableStateOf(StoryComposerStage.COMPOSE) }
    val screenTitle = stringResource(
        if (isEditMode) R.string.story_edit_title else R.string.story_compose_title
    )
    val composerTitle = state.chatTitle.ifBlank {
        stringResource(
            if (isEditMode) R.string.story_edit_title else R.string.story_compose_title
        )
    }
    val stageSubtitle = when (stage) {
        StoryComposerStage.COMPOSE -> {
            stringResource(
                if (isEditMode) R.string.story_edit_as else R.string.story_post_as,
                composerTitle
            )
        }

        StoryComposerStage.PREVIEW -> stringResource(R.string.story_preview_subtitle)
        StoryComposerStage.PHOTO_EDITOR -> stringResource(R.string.story_photo_editor_title)
        StoryComposerStage.VIDEO_EDITOR -> stringResource(R.string.story_video_editor_title)
    }

    LaunchedEffect(hasMedia) {
        if (!hasMedia && stage != StoryComposerStage.COMPOSE) {
            stage = StoryComposerStage.COMPOSE
        }
    }

    if (stage == StoryComposerStage.PHOTO_EDITOR && hasMedia) {
        PhotoEditorScreen(
            imagePath = state.composerDraft.sourcePath,
            onClose = { stage = StoryComposerStage.COMPOSE },
            onSave = { editedPath ->
                component.attachMedia(editedPath, StoryMediaType.PHOTO)
                stage = StoryComposerStage.COMPOSE
            }
        )
        return
    }

    if (stage == StoryComposerStage.VIDEO_EDITOR && hasMedia) {
        VideoEditorScreen(
            videoPath = state.composerDraft.sourcePath,
            onClose = { stage = StoryComposerStage.COMPOSE },
            onSave = { editedPath ->
                component.attachMedia(editedPath, StoryMediaType.VIDEO)
                stage = StoryComposerStage.COMPOSE
            }
        )
        return
    }

    BackHandler(enabled = stage == StoryComposerStage.PREVIEW) {
        stage = StoryComposerStage.COMPOSE
    }

    val galleryPermissions = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )

            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val fullGalleryPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val requestableGalleryPermissions = remember(context, galleryPermissions) {
        val declared = context.declaredPermissions()
        galleryPermissions.filter { it in declared }
    }
    val requestableFullGalleryPermissions = remember(context, fullGalleryPermissions) {
        val declared = context.declaredPermissions()
        fullGalleryPermissions.filter { it in declared }
    }

    fun hasPartialGalleryPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasFullGalleryPermission(): Boolean {
        return requestableFullGalleryPermissions.isEmpty() ||
                context.hasAllPermissions(requestableFullGalleryPermissions)
    }

    var hasGalleryAccess by remember {
        mutableStateOf(hasFullGalleryPermission() || hasPartialGalleryPermission())
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasGalleryAccess = hasFullGalleryPermission() || hasPartialGalleryPermission()
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            component.showCamera()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = screenTitle,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stageSubtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = component::dismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (stage == StoryComposerStage.PREVIEW) {
                                stage = StoryComposerStage.COMPOSE
                            } else if (hasMedia) {
                                stage = StoryComposerStage.PREVIEW
                            } else {
                                component.openMediaPicker()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = if (stage == StoryComposerStage.PREVIEW) {
                                Icons.Rounded.Edit
                            } else if (hasMedia) {
                                Icons.Rounded.PlayArrow
                            } else {
                                Icons.Rounded.PhotoLibrary
                            },
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            stringResource(
                                when {
                                    stage == StoryComposerStage.PREVIEW -> R.string.story_back_to_editor
                                    hasMedia -> R.string.story_preview
                                    else -> R.string.story_pick_media
                                }
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = component::saveStory,
                        enabled = state.composerDraft.isValid &&
                                !state.isSubmitting &&
                                (isEditMode || canPublishStory(state.postCapability)),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        Text(
                            if (state.isSubmitting) {
                                stringResource(
                                    if (isEditMode) R.string.story_saving else R.string.story_posting
                                )
                            } else {
                                stringResource(R.string.story_publish_short)
                            },
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
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            AnimatedContent(
                targetState = stage,
                label = "StoryComposerStage",
                transitionSpec = {
                    (fadeIn() + slideInVertically(initialOffsetY = { it / 12 })) togetherWith
                            (fadeOut() + slideOutVertically(targetOffsetY = { it / 14 }))
                }
            ) { currentStage ->
                when (currentStage) {
                    StoryComposerStage.COMPOSE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            StorySectionHeader(
                                title = stringResource(R.string.story_preview_title),
                                subtitle = stringResource(R.string.story_preview_subtitle)
                            )
                            StoryComposerPreviewCard(
                                state = state,
                                composerTitle = composerTitle,
                                subtitle = stageSubtitle,
                                onSelectMedia = component::openMediaPicker,
                                onOpenPreview = { stage = StoryComposerStage.PREVIEW },
                                onSelectMediaPage = component::selectComposerMedia
                            )

                            StorySectionHeader(
                                title = stringResource(R.string.story_change_media),
                                subtitle = stringResource(R.string.story_select_media_hint)
                            )
                            StoryComposerActionList(
                                hasMedia = hasMedia,
                                mediaType = state.composerDraft.mediaType,
                                onSelectMedia = component::openMediaPicker,
                                onOpenCamera = {
                                    if (hasCameraPermission) {
                                        component.showCamera()
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                onOpenEditor = {
                                    if (hasMedia) {
                                        stage = when (state.composerDraft.mediaType) {
                                            StoryMediaType.VIDEO -> StoryComposerStage.VIDEO_EDITOR
                                            StoryMediaType.PHOTO -> StoryComposerStage.PHOTO_EDITOR
                                        }
                                    }
                                },
                                onOpenPreview = { stage = StoryComposerStage.PREVIEW }
                            )

                            state.inlineError?.let { message ->
                                Spacer(modifier = Modifier.size(12.dp))
                                StoryErrorBanner(message = message)
                            }

                            StorySectionHeader(
                                title = stringResource(R.string.story_details_title),
                                subtitle = stringResource(R.string.story_caption_supporting)
                            )
                            SettingsTextField(
                                value = state.composerDraft.caption,
                                onValueChange = component::updateCaption,
                                placeholder = stringResource(R.string.story_caption_label),
                                icon = Icons.Rounded.Edit,
                                position = ItemPosition.STANDALONE,
                                minLines = 2,
                                maxLines = 4
                            )

                            if (!isEditMode) {
                                StorySectionHeader(
                                    title = stringResource(R.string.story_privacy_label),
                                    subtitle = null
                                )
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    StoryChoiceSurface {
                                        StoryPrivacySectionComponent(
                                            selected = when (state.composerDraft.privacy.mode) {
                                                StoryPrivacyMode.CONTACTS -> StoryPrivacyUi.CONTACTS
                                                StoryPrivacyMode.CLOSE_FRIENDS -> StoryPrivacyUi.CLOSE_FRIENDS
                                                StoryPrivacyMode.SELECTED_USERS -> StoryPrivacyUi.SELECTED_USERS
                                                else -> StoryPrivacyUi.EVERYONE
                                            },
                                            onSelect = component::updatePrivacy
                                        )
                                    }
                                    AnimatedVisibility(
                                        visible = state.composerDraft.privacy.mode != StoryPrivacyMode.CLOSE_FRIENDS,
                                        enter = fadeIn() + expandVertically(),
                                        exit = fadeOut() + shrinkVertically()
                                    ) {
                                        StoryAudienceFilterRowComponent(
                                            privacy = state.composerDraft.privacy,
                                            onClick = {
                                                component.showAudiencePicker(
                                                    when (state.composerDraft.privacy.mode) {
                                                        StoryPrivacyMode.SELECTED_USERS -> {
                                                            StoryAudienceFilterMode.SHOW_TO
                                                        }

                                                        else -> StoryAudienceFilterMode.HIDE_FROM
                                                    }
                                                )
                                            }
                                        )
                                    }
                                }

                                StorySectionHeader(
                                    title = stringResource(R.string.story_duration_label),
                                    subtitle = stringResource(R.string.story_duration_supporting)
                                )
                                StoryChoiceSurface {
                                    StoryDurationSectionComponent(
                                        selectedSeconds = state.composerDraft.activePeriodSeconds,
                                        onSelect = component::updateActivePeriod
                                    )
                                }

                                StorySectionHeader(
                                    title = stringResource(R.string.story_settings_title),
                                    subtitle = stringResource(R.string.story_audience_timing_subtitle)
                                )
                                SettingsSwitchTile(
                                    icon = Icons.Rounded.Person,
                                    title = stringResource(R.string.story_keep_on_profile),
                                    subtitle = stringResource(R.string.story_keep_on_profile_subtitle),
                                    checked = state.composerDraft.keepOnProfile,
                                    iconColor = MaterialTheme.colorScheme.primary,
                                    position = ItemPosition.TOP,
                                    onCheckedChange = component::updateKeepOnProfile
                                )
                                SettingsSwitchTile(
                                    icon = Icons.Rounded.Shield,
                                    title = stringResource(R.string.story_protect_content),
                                    subtitle = stringResource(R.string.story_protect_content_subtitle),
                                    checked = state.composerDraft.protectContent,
                                    iconColor = MaterialTheme.colorScheme.tertiary,
                                    position = ItemPosition.BOTTOM,
                                    onCheckedChange = component::updateProtectContent
                                )
                            }

                            if (!state.composerDraft.widgetLink.isNullOrBlank()) {
                                StorySectionHeader(
                                    title = stringResource(R.string.story_widget_link_title),
                                    subtitle = stringResource(R.string.story_widget_link_subtitle)
                                )
                                StoryWidgetLinkRow(link = state.composerDraft.widgetLink.orEmpty())
                            }

                            capability?.takeIf { !isEditMode }?.let { presentation ->
                                StorySectionHeader(
                                    title = stringResource(R.string.story_publish),
                                    subtitle = null
                                )
                                StoryCapabilityFooter(presentation = presentation)
                            }

                            Spacer(modifier = Modifier.size(16.dp))
                        }
                    }

                    StoryComposerStage.PREVIEW -> {
                        StoryComposerPreviewStage(
                            state = state,
                            onSelectMediaPage = component::selectComposerMedia,
                            onOpenEditor = {
                                if (hasMedia) {
                                    stage = when (state.composerDraft.mediaType) {
                                        StoryMediaType.VIDEO -> StoryComposerStage.VIDEO_EDITOR
                                        StoryMediaType.PHOTO -> StoryComposerStage.PHOTO_EDITOR
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    StoryComposerStage.PHOTO_EDITOR,
                    StoryComposerStage.VIDEO_EDITOR -> Unit
                }
            }
        }
    }

    if (state.audiencePicker.isVisible) {
        ModalBottomSheet(
            onDismissRequest = component::dismissAudiencePicker,
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            StoryAudiencePickerContentComponent(
                state = state.audiencePicker,
                onDismiss = component::dismissAudiencePicker,
                onSearchQueryChange = component::updateAudienceSearchQuery,
                onToggleUserSelection = component::toggleAudienceUserSelection,
                onClearSelection = component::clearAudienceSelection
            )
        }
    }

    if (state.showMediaPicker) {
        ModalBottomSheet(
            onDismissRequest = component::dismissMediaPicker,
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            GalleryScreen(
                onMediaSelected = { uris ->
                    val items = uris.mapNotNull { uri ->
                        val path = context.copyUriToTempPath(uri) ?: return@mapNotNull null
                        StoryComposerMediaItemModel(
                            sourcePath = path,
                            mediaType = inferPickedStoryMediaType(context, uri, path)
                        )
                    }
                    component.attachMedia(items)
                },
                onDismiss = component::dismissMediaPicker,
                onCameraClick = {
                    if (hasCameraPermission) {
                        component.showCamera()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                canSelectMedia = true,
                canUseCamera = true,
                canAttachFiles = false,
                canCreatePoll = false,
                canCreateChecklist = false,
                onAttachFileClick = {},
                onCreatePollClick = {},
                onCreateChecklistClick = {},
                attachBots = emptyList(),
                hasMediaAccess = hasGalleryAccess || hasFullGalleryPermission() || hasPartialGalleryPermission(),
                isPartialAccess = hasPartialGalleryPermission() && !hasFullGalleryPermission(),
                onPickFromOtherSources = {},
                onRequestMediaAccess = {
                    if (requestableGalleryPermissions.isNotEmpty()) {
                        galleryPermissionLauncher.launch(requestableGalleryPermissions.toTypedArray())
                    }
                },
                onAttachBotClick = {},
                modifier = Modifier.fillMaxHeight()
            )
        }
    }

    if (state.showCamera) {
        CameraScreen(
            onImageCaptured = { uri ->
                val path = context.copyUriToTempPath(uri)
                if (path != null) {
                    component.attachMedia(path, StoryMediaType.PHOTO)
                } else {
                    component.dismissCamera()
                }
            },
            onDismiss = component::dismissCamera
        )
    }
}

@Composable
private fun StorySectionHeader(
    title: String,
    subtitle: String?
) {
    Column(
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StoryChoiceSurface(
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun StoryWidgetLinkRow(link: String) {
    SettingsTile(
        icon = Icons.Rounded.Link,
        title = stringResource(R.string.story_widget_link_title),
        subtitle = link,
        iconColor = MaterialTheme.colorScheme.primary,
        position = ItemPosition.STANDALONE,
        onClick = {}
    )
}

@Composable
private fun StoryComposerIdentityAvatar(
    title: String,
    avatarPath: String?
) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        if (!avatarPath.isNullOrBlank()) {
            AsyncImage(
                model = avatarPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.trim().take(1).ifBlank { "S" }.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun StoryComposerActionList(
    hasMedia: Boolean,
    mediaType: StoryMediaType,
    onSelectMedia: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenPreview: () -> Unit
) {
    val items = buildList {
        add(
            StoryComposerActionModel(
                icon = Icons.Rounded.PhotoLibrary,
                title = if (hasMedia) {
                    R.string.story_change_media
                } else {
                    R.string.gallery_title_attachments
                },
                subtitle = if (hasMedia) {
                    R.string.story_pick_media
                } else {
                    R.string.story_select_media_hint
                },
                iconColor = MaterialTheme.colorScheme.primary,
                enabled = true,
                onClick = onSelectMedia
            )
        )
        add(
            StoryComposerActionModel(
                icon = Icons.Rounded.CameraAlt,
                title = R.string.permission_camera_title,
                subtitle = R.string.story_pick_media,
                iconColor = MaterialTheme.colorScheme.tertiary,
                enabled = true,
                onClick = onOpenCamera
            )
        )
        if (hasMedia) {
            add(
                StoryComposerActionModel(
                    icon = Icons.Rounded.Edit,
                    title = if (mediaType == StoryMediaType.VIDEO) {
                        R.string.story_open_video_editor
                    } else {
                        R.string.story_open_photo_editor
                    },
                    subtitle = R.string.story_back_to_editor,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    enabled = true,
                    onClick = onOpenEditor
                )
            )
            add(
                StoryComposerActionModel(
                    icon = Icons.Rounded.PlayArrow,
                    title = R.string.story_preview,
                    subtitle = R.string.story_preview_subtitle,
                    iconColor = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    onClick = onOpenPreview
                )
            )
        }
    }

    items.forEachIndexed { index, item ->
        val position = when {
            items.size == 1 -> ItemPosition.STANDALONE
            index == 0 -> ItemPosition.TOP
            index == items.lastIndex -> ItemPosition.BOTTOM
            else -> ItemPosition.MIDDLE
        }
        SettingsTile(
            icon = item.icon,
            title = stringResource(item.title),
            subtitle = stringResource(item.subtitle),
            iconColor = item.iconColor,
            position = position,
            enabled = item.enabled,
            onClick = item.onClick
        )
    }
}

@Composable
private fun StoryCapabilityFooter(
    presentation: StoryCapabilityPresentation
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (presentation.isBlocking) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (presentation.isBlocking) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (presentation.isBlocking) {
                            Icons.Rounded.Schedule
                        } else {
                            Icons.Rounded.Add
                        },
                        contentDescription = null,
                        tint = if (presentation.isBlocking) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.story_publish),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (presentation.isBlocking) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
                Text(
                    text = presentation.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (presentation.isBlocking) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
        }
    }
}

@Composable
private fun StoryPreviewDetailsPanel(
    state: StoriesHostComponent.State,
    isVideoMuted: Boolean,
    isVideoPaused: Boolean,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenEditor: () -> Unit
) {
    val hasVideoControls = state.composerDraft.mediaType == StoryMediaType.VIDEO
    val rows = buildList {
        add(
            StoryComposerActionModel(
                icon = Icons.Rounded.Edit,
                title = if (state.composerDraft.mediaType == StoryMediaType.VIDEO) {
                    R.string.story_open_video_editor
                } else {
                    R.string.story_open_photo_editor
                },
                subtitle = R.string.story_back_to_editor,
                iconColor = MaterialTheme.colorScheme.primary,
                enabled = state.composerDraft.isValid,
                onClick = onOpenEditor
            )
        )
        add(
            StoryComposerActionModel(
                icon = when (state.composerDraft.privacy.mode) {
                    StoryPrivacyMode.CONTACTS -> Icons.Rounded.PeopleAlt
                    StoryPrivacyMode.CLOSE_FRIENDS -> Icons.Rounded.Favorite
                    StoryPrivacyMode.SELECTED_USERS -> Icons.Rounded.Person
                    else -> Icons.Rounded.Public
                },
                title = R.string.story_privacy_label,
                subtitle = 0,
                iconColor = MaterialTheme.colorScheme.secondary,
                enabled = true,
                onClick = {}
            )
        )
        add(
            StoryComposerActionModel(
                icon = Icons.Rounded.Schedule,
                title = R.string.story_duration_label,
                subtitle = 0,
                iconColor = MaterialTheme.colorScheme.tertiary,
                enabled = true,
                onClick = {}
            )
        )
        if (!state.composerDraft.widgetLink.isNullOrBlank()) {
            add(
                StoryComposerActionModel(
                    icon = Icons.Rounded.Link,
                    title = R.string.story_widget_link_title,
                    subtitle = 0,
                    iconColor = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    onClick = {}
                )
            )
        }
    }
    val totalItems = rows.size + if (hasVideoControls) 2 else 0

    fun positionForIndex(index: Int): ItemPosition {
        return when {
            totalItems <= 1 -> ItemPosition.STANDALONE
            index == 0 -> ItemPosition.TOP
            index == totalItems - 1 -> ItemPosition.BOTTOM
            else -> ItemPosition.MIDDLE
        }
    }

    Column {
        rows.forEachIndexed { index, item ->
            val subtitleText = when (index) {
                1 -> formatStoryPrivacySummary(state.composerDraft.privacy)

                2 -> formatStoryDurationLabel(state.composerDraft.activePeriodSeconds)
                3 -> state.composerDraft.widgetLink.orEmpty()
                    .takeIf { item.icon == Icons.Rounded.Link }

                else -> if (item.subtitle != 0) stringResource(item.subtitle) else null
            }
            SettingsTile(
                icon = item.icon,
                title = stringResource(item.title),
                subtitle = subtitleText,
                iconColor = item.iconColor,
                position = positionForIndex(index),
                enabled = item.enabled,
                onClick = item.onClick
            )
        }

        if (hasVideoControls) {
            SettingsSwitchTile(
                icon = if (isVideoPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                title = stringResource(if (isVideoPaused) R.string.story_play else R.string.story_pause),
                subtitle = null,
                checked = !isVideoPaused,
                iconColor = MaterialTheme.colorScheme.primary,
                position = positionForIndex(rows.size),
                onCheckedChange = { onTogglePlay() }
            )
            SettingsSwitchTile(
                icon = if (isVideoMuted) {
                    Icons.AutoMirrored.Rounded.VolumeOff
                } else {
                    Icons.AutoMirrored.Rounded.VolumeUp
                },
                title = stringResource(if (isVideoMuted) R.string.story_audio_unmute else R.string.story_audio_mute),
                subtitle = null,
                checked = !isVideoMuted,
                iconColor = MaterialTheme.colorScheme.tertiary,
                position = positionForIndex(rows.size + 1),
                onCheckedChange = { onToggleMute() }
            )
        }
    }
}

@Composable
private fun formatStoryPrivacySummary(privacy: StoryPrivacySettingsModel): String {
    val baseLabel = when (privacy.mode) {
        StoryPrivacyMode.EVERYONE -> stringResource(R.string.story_privacy_everyone)
        StoryPrivacyMode.CONTACTS -> stringResource(R.string.story_privacy_contacts)
        StoryPrivacyMode.CLOSE_FRIENDS -> stringResource(R.string.story_privacy_close_friends)
        StoryPrivacyMode.SELECTED_USERS -> stringResource(R.string.story_privacy_selected_users)
    }
    val filterSummary = when {
        privacy.mode == StoryPrivacyMode.SELECTED_USERS && privacy.selectedUserIds.isEmpty() -> {
            stringResource(R.string.story_privacy_show_to_empty)
        }

        privacy.mode == StoryPrivacyMode.SELECTED_USERS -> {
            pluralStringResource(
                R.plurals.story_privacy_show_to_count,
                privacy.selectedUserIds.size,
                privacy.selectedUserIds.size
            )
        }

        privacy.exceptUserIds.isNotEmpty() -> {
            pluralStringResource(
                R.plurals.story_privacy_hide_from_count,
                privacy.exceptUserIds.size,
                privacy.exceptUserIds.size
            )
        }

        else -> null
    }

    return if (filterSummary.isNullOrBlank()) {
        baseLabel
    } else {
        "$baseLabel, $filterSummary"
    }
}

@Composable
private fun StoryComposerPreviewCard(
    state: StoriesHostComponent.State,
    composerTitle: String,
    subtitle: String,
    onSelectMedia: () -> Unit,
    onOpenPreview: () -> Unit,
    onSelectMediaPage: (Int) -> Unit
) {
    val mediaItems = state.composerDraft.mediaItems
    val hasMedia = mediaItems.isNotEmpty()

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StoryComposerIdentityAvatar(
                    title = composerTitle,
                    avatarPath = state.chatAvatarPath
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = composerTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.size(1.dp))
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val maxPreviewHeight = 360.dp
                val maxPreviewWidth = maxPreviewHeight * STORY_MEDIA_ASPECT_RATIO
                val previewWidth = if (maxWidth < maxPreviewWidth) maxWidth else maxPreviewWidth

                if (!hasMedia) {
                    Box(
                        modifier = Modifier
                            .width(previewWidth)
                            .aspectRatio(STORY_MEDIA_ASPECT_RATIO)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = onSelectMedia),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.story_pick_media),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.story_select_media_hint),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    StoryComposerMediaPager(
                        mediaItems = mediaItems,
                        selectedIndex = state.composerDraft.selectedMediaIndex,
                        caption = state.composerDraft.caption,
                        onPageChanged = onSelectMediaPage,
                        modifier = Modifier
                            .width(previewWidth)
                            .aspectRatio(STORY_MEDIA_ASPECT_RATIO),
                        onClick = onOpenPreview
                    )
                }
            }

            if (hasMedia) {
                StoryComposerPagerFooter(
                    mediaItems = mediaItems,
                    selectedIndex = state.composerDraft.selectedMediaIndex
                )
            }
        }
    }
}

@Composable
private fun StoryComposerPreviewStage(
    state: StoriesHostComponent.State,
    onSelectMediaPage: (Int) -> Unit,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mediaItems = state.composerDraft.mediaItems
    var isVideoMuted by rememberSaveable(state.composerDraft.sourcePath) { mutableStateOf(true) }
    var isVideoPaused by rememberSaveable(state.composerDraft.sourcePath) { mutableStateOf(false) }
    var isVideoBuffering by rememberSaveable(state.composerDraft.sourcePath) { mutableStateOf(false) }
    var restartPlaybackToken by rememberSaveable(state.composerDraft.sourcePath) { mutableStateOf(0) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val maxPreviewHeight = 560.dp
            val maxPreviewWidth = maxPreviewHeight * STORY_MEDIA_ASPECT_RATIO
            val previewWidth = if (maxWidth < maxPreviewWidth) maxWidth else maxPreviewWidth

            Surface(
                modifier = Modifier
                    .width(previewWidth)
                    .aspectRatio(STORY_MEDIA_ASPECT_RATIO),
                shape = RoundedCornerShape(28.dp),
                color = Color.Black
            ) {
                StoryComposerPreviewPager(
                    mediaItems = mediaItems,
                    selectedIndex = state.composerDraft.selectedMediaIndex,
                    caption = state.composerDraft.caption,
                    isVideoMuted = isVideoMuted,
                    isVideoPaused = isVideoPaused,
                    restartPlaybackToken = restartPlaybackToken,
                    isVideoBuffering = isVideoBuffering,
                    onPageChanged = onSelectMediaPage,
                    onBufferingChange = { isVideoBuffering = it },
                    onPlayingChange = { playing -> isVideoPaused = !playing },
                    onCompleted = {
                        isVideoPaused = true
                        restartPlaybackToken += 1
                    }
                )
            }
        }

        if (mediaItems.isNotEmpty()) {
            StoryComposerPagerFooter(
                mediaItems = mediaItems,
                selectedIndex = state.composerDraft.selectedMediaIndex
            )
        }

        StorySectionHeader(
            title = stringResource(R.string.story_settings_title),
            subtitle = stringResource(R.string.story_preview_subtitle)
        )
        StoryPreviewDetailsPanel(
            state = state,
            isVideoMuted = isVideoMuted,
            isVideoPaused = isVideoPaused,
            onTogglePlay = { isVideoPaused = !isVideoPaused },
            onToggleMute = { isVideoMuted = !isVideoMuted },
            onOpenEditor = onOpenEditor
        )
    }
}

@Composable
private fun StoryComposerMediaPager(
    mediaItems: List<StoryComposerMediaItemModel>,
    selectedIndex: Int,
    caption: String,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val pagerState = rememberPagerState(
        initialPage = selectedIndex.coerceIn(0, mediaItems.lastIndex),
        pageCount = { mediaItems.size }
    )

    LaunchedEffect(mediaItems.size, selectedIndex) {
        val targetPage = selectedIndex.coerceIn(0, mediaItems.lastIndex)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage, mediaItems.size) {
        if (mediaItems.isEmpty()) return@LaunchedEffect
        val currentPage = pagerState.currentPage.coerceIn(0, mediaItems.lastIndex)
        if (currentPage != selectedIndex) {
            onPageChanged(currentPage)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        userScrollEnabled = mediaItems.size > 1
    ) { page ->
        val mediaItem = mediaItems[page]
        StoryComposerMediaPage(
            mediaItem = mediaItem,
            caption = caption,
            isActive = page == pagerState.currentPage,
            onClick = onClick
        )
    }
}

@Composable
private fun StoryComposerMediaPage(
    mediaItem: StoryComposerMediaItemModel,
    caption: String,
    isActive: Boolean,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val previewModel = rememberStoryImageModel(
        context = context,
        primaryPath = mediaItem.sourcePath.takeIf { it.isNotBlank() },
        fallbackPath = null,
        minithumbnail = null
    )
    var isVideoBuffering by rememberSaveable(mediaItem.sourcePath) { mutableStateOf(false) }
    var restartPlaybackToken by rememberSaveable(mediaItem.sourcePath) { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) {
        when {
            mediaItem.sourcePath.isBlank() -> {
                StoryUnavailablePlaceholder(stringResource(R.string.story_pick_media))
            }

            mediaItem.mediaType == StoryMediaType.VIDEO && File(mediaItem.sourcePath).exists() -> {
                StoryInlineVideoPlayer(
                    path = mediaItem.sourcePath,
                    previewModel = previewModel,
                    previewContentScale = ContentScale.Crop,
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    isMuted = true,
                    isPlaying = isActive,
                    restartPlaybackToken = restartPlaybackToken,
                    onProgress = {},
                    onBufferingChange = { isVideoBuffering = it },
                    onPlayingChange = {},
                    onCompleted = { restartPlaybackToken += 1 }
                )
            }

            previewModel != null -> {
                AsyncImage(
                    model = previewModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.38f)
                        )
                    )
                )
        )

        if (caption.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.38f)
            ) {
                Text(
                    text = caption,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }

        if (mediaItem.mediaType == StoryMediaType.VIDEO) {
            StoryComposerPreviewBadge(
                text = stringResource(R.string.story_media_video),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            )
        }

        AnimatedVisibility(
            visible = isVideoBuffering,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.42f)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(16.dp)
                        .size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp
                )
            }
        }
    }
}

@Composable
private fun StoryComposerPreviewPager(
    mediaItems: List<StoryComposerMediaItemModel>,
    selectedIndex: Int,
    caption: String,
    isVideoMuted: Boolean,
    isVideoPaused: Boolean,
    restartPlaybackToken: Int,
    isVideoBuffering: Boolean,
    onPageChanged: (Int) -> Unit,
    onBufferingChange: (Boolean) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onCompleted: () -> Unit
) {
    if (mediaItems.isEmpty()) {
        StoryUnavailablePlaceholder(stringResource(R.string.story_pick_media))
        return
    }
    val context = LocalContext.current

    val pagerState = rememberPagerState(
        initialPage = selectedIndex.coerceIn(0, mediaItems.lastIndex),
        pageCount = { mediaItems.size }
    )

    LaunchedEffect(mediaItems.size, selectedIndex) {
        val targetPage = selectedIndex.coerceIn(0, mediaItems.lastIndex)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage, mediaItems.size) {
        val currentPage = pagerState.currentPage.coerceIn(0, mediaItems.lastIndex)
        if (currentPage != selectedIndex) {
            onPageChanged(currentPage)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        userScrollEnabled = mediaItems.size > 1
    ) { page ->
        val mediaItem = mediaItems[page]
        val pagePreviewModel = rememberStoryImageModel(
            context = context,
            primaryPath = mediaItem.sourcePath.takeIf { it.isNotBlank() },
            fallbackPath = null,
            minithumbnail = null
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when {
                mediaItem.sourcePath.isBlank() -> {
                    StoryUnavailablePlaceholder(stringResource(R.string.story_pick_media))
                }

                mediaItem.mediaType == StoryMediaType.VIDEO && File(mediaItem.sourcePath).exists() -> {
                    StoryInlineVideoPlayer(
                        path = mediaItem.sourcePath,
                        previewModel = pagePreviewModel,
                        previewContentScale = ContentScale.Crop,
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                        isMuted = isVideoMuted,
                        isPlaying = !isVideoPaused && page == pagerState.currentPage,
                        restartPlaybackToken = restartPlaybackToken,
                        onProgress = {},
                        onBufferingChange = {
                            if (page == pagerState.currentPage) {
                                onBufferingChange(it)
                            }
                        },
                        onPlayingChange = {
                            if (page == pagerState.currentPage) {
                                onPlayingChange(it)
                            }
                        },
                        onCompleted = {
                            if (page == pagerState.currentPage) {
                                onCompleted()
                            }
                        }
                    )
                }

                pagePreviewModel != null -> {
                    AsyncImage(
                        model = pagePreviewModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.18f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.58f)
                            )
                        )
                    )
            )

            if (caption.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.42f)
                ) {
                    Text(
                        text = caption,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }

            if (mediaItem.mediaType == StoryMediaType.VIDEO) {
                StoryComposerPreviewBadge(
                    text = stringResource(R.string.story_media_video),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                )
            }

            AnimatedVisibility(
                visible = isVideoBuffering && page == pagerState.currentPage,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.42f)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(18.dp)
                            .size(28.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryComposerPagerFooter(
    mediaItems: List<StoryComposerMediaItemModel>,
    selectedIndex: Int
) {
    val currentMedia = mediaItems.getOrNull(selectedIndex) ?: mediaItems.firstOrNull() ?: return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StoryComposerPreviewBadge(
                text = stringResource(
                    if (currentMedia.mediaType == StoryMediaType.VIDEO) {
                        R.string.story_media_video
                    } else {
                        R.string.story_media_photo
                    }
                )
            )
            if (mediaItems.size > 1) {
                StoryComposerPreviewBadge(
                    text = stringResource(
                        R.string.story_viewer_item_position,
                        selectedIndex + 1,
                        mediaItems.size
                    )
                )
            }
        }

        if (mediaItems.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                mediaItems.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (index == selectedIndex) 18.dp else 6.dp, 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == selectedIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryComposerPreviewBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.32f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

private fun inferPickedStoryMediaType(
    context: Context,
    uri: Uri,
    resolvedPath: String
): StoryMediaType {
    val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase()
    if (mimeType.startsWith("video/")) {
        return StoryMediaType.VIDEO
    }
    if (mimeType.startsWith("image/")) {
        return StoryMediaType.PHOTO
    }
    val pathType = inferUriMediaType(Uri.parse(resolvedPath))
    if (pathType == StoryMediaType.VIDEO) {
        return pathType
    }
    return inferUriMediaType(uri)
}
