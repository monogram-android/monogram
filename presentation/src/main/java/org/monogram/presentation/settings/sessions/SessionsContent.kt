@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.sessions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.IntegratedQRScanner
import org.monogram.presentation.core.ui.spacer.HeightSpacer
import org.monogram.presentation.core.ui.toolbar.Toolbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsContent(component: SessionsComponent) {
    val state by component.state.subscribeAsState()
    val screenTitleRes = if (state.isScanning) R.string.scan_qr_title else R.string.devices_title

    if (state.showConfirmation) {
        ConfirmDialog(
            onConfirm = component::confirmAuth,
            onDismiss = component::dismissAuth,
        )
    }

    Scaffold(
        topBar = {
            Toolbar(
                title = stringResource(screenTitleRes),
                onNavigationClick = {
                    if (state.isScanning) component.toggleScanner(false) else component.onBackClicked()
                }
            )
        },
        floatingActionButton = {
            if (!state.isScanning) {
                QrFabButton(
                    onToggleClick = { component.toggleScanner(true) }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val (current, others) = state.sessions.partition { it.isCurrent }
            val (pending, active) = others.partition { it.isUnconfirmed }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                sessionSection(
                    titleRes = R.string.current_device_section,
                    items = current,
                    onTerminateClick = null
                )

                sessionSection(
                    titleRes = R.string.login_requests_section,
                    items = pending,
                    onTerminateClick = { session ->
                        component.terminateSession(session.id)
                    }
                )

                sessionSection(
                    titleRes = R.string.active_sessions_section,
                    items = active,
                    onTerminateClick = { session ->
                        component.terminateSession(session.id)
                    }
                )

                item { HeightSpacer(padding.calculateBottomPadding()) }
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
private fun ConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirmation_title)) },
        text = { Text(stringResource(R.string.confirmation_message)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.confirm_auth_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_action))
            }
        }
    )
}

@Composable
private fun QrFabButton(
    onToggleClick: () -> Unit,
) {
    val context = LocalContext.current
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onToggleClick()
        }
    }

    fun checkPermissionAndScan() {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> onToggleClick()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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