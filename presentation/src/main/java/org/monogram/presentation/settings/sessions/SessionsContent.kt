@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.sessions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.delay
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.IntegratedQRScanner
import org.monogram.presentation.core.ui.ItemPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsContent(component: SessionsComponent) {
    val state by component.state.subscribeAsState()
    val context = LocalContext.current
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            component.toggleScanner(true)
        }
    }

    fun checkPermissionAndScan() {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> component.toggleScanner(true)
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    if (state.showConfirmation) {
        AlertDialog(
            onDismissRequest = component::dismissAuth,
            title = { Text(stringResource(R.string.confirmation_title)) },
            text = { Text(stringResource(R.string.confirmation_message)) },
            confirmButton = {
                Button(onClick = component::confirmAuth) {
                    Text(stringResource(R.string.confirm_auth_action))
                }
            },
            dismissButton = {
                TextButton(onClick = component::dismissAuth) {
                    Text(stringResource(R.string.cancel_action))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.isScanning)
                            stringResource(R.string.scan_qr_title)
                        else
                            stringResource(R.string.devices_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.isScanning) component.toggleScanner(false) else component.onBackClicked()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (!state.isScanning) {
                ExtendedFloatingActionButton(
                    onClick = { checkPermissionAndScan() },
                    icon = {
                        Icon(
                            Icons.Rounded.QrCodeScanner,
                            contentDescription = stringResource(R.string.cd_qr_icon)
                        )
                    },
                    text = { Text(stringResource(R.string.link_device_action)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize()
        ) {
            val (current, others) = state.sessions.partition { it.isCurrent }
            val (pending, active) = others.partition { it.isUnconfirmed }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (current.isNotEmpty()) {
                    item {
                        SectionHeader(stringResource(R.string.current_device_section))
                    }
                    items(current, key = { it.id }) { session ->
                        SessionItem(
                            session = session,
                            isPending = false,
                            position = ItemPosition.STANDALONE,
                            onTerminate = null
                        )
                    }
                }

                if (pending.isNotEmpty()) {
                    item {
                        SectionHeader(stringResource(R.string.login_requests_section))
                    }
                    items(pending, key = { it.id }) { session ->
                        var isVisible by remember { mutableStateOf(true) }
                        LaunchedEffect(isVisible) {
                            if (!isVisible) {
                                delay(350)
                                component.terminateSession(session.id)
                            }
                        }

                        AnimatedVisibility(
                            visible = isVisible,
                            exit = shrinkVertically(animationSpec = tween(300)) +
                                    fadeOut(animationSpec = tween(300))
                        ) {
                            SessionItem(
                                session = session,
                                isPending = true,
                                position = ItemPosition.STANDALONE
                            ) {
                                isVisible = false
                            }
                        }
                    }
                }

                if (active.isNotEmpty()) {
                    item {
                        SectionHeader(stringResource(R.string.active_sessions_section))
                    }
                    items(active, key = { it.id }) { session ->
                        var isVisible by remember { mutableStateOf(true) }
                        LaunchedEffect(isVisible) {
                            if (!isVisible) {
                                delay(350)
                                component.terminateSession(session.id)
                            }
                        }

                        AnimatedVisibility(
                            visible = isVisible,
                            exit = shrinkVertically(animationSpec = tween(300)) +
                                    fadeOut(animationSpec = tween(300))
                        ) {
                            SessionItem(
                                session = session,
                                isPending = false,
                                position = ItemPosition.STANDALONE
                            ) {
                                isVisible = false
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(padding.calculateBottomPadding())) }
            }
            if (state.isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    IntegratedQRScanner(
                        onCodeDetected = component::onQrCodeScanned,
                        onBackClicked = { component.toggleScanner(false) }
                    )
                }
            }

            if (state.isLoading) {
                ContainedLoadingIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}