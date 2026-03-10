package org.monogram.presentation.features.webapp.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.monogram.presentation.core.ui.IntegratedQRScanner

@Composable
fun MiniAppQrScanner(
    qrText: String?,
    onCodeDetected: (String) -> Unit,
    onBackClicked: () -> Unit
) {
    if (qrText != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            IntegratedQRScanner(
                onCodeDetected = onCodeDetected,
                onBackClicked = onBackClicked
            )

            if (qrText.isNotEmpty()) {
                Text(
                    text = qrText,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp)
                        .padding(horizontal = 32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
