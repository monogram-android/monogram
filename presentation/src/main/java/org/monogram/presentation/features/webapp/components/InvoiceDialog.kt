package org.monogram.presentation.features.webapp.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.monogram.domain.models.webapp.InvoiceModel
import org.monogram.domain.repository.FileRepository
import org.monogram.domain.repository.PaymentRepository

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InvoiceDialog(
    slug: String? = null,
    chatId: Long? = null,
    messageId: Long? = null,
    paymentRepository: PaymentRepository,
    fileRepository: FileRepository,
    onDismiss: (status: String) -> Unit
) {
    var invoice by remember { mutableStateOf<InvoiceModel?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isPaying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var photoPath by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(slug, chatId, messageId) {
        val inv = paymentRepository.getInvoice(slug = slug, chatId = chatId, messageId = messageId)
        invoice = inv
        isLoading = false

        inv?.photoUrl?.let { fileIdStr ->
            val fileId = fileIdStr.toIntOrNull()
            if (fileId != null) {
                photoPath = fileRepository.getFilePath(fileId)
                if (photoPath == null) {
                    fileRepository.downloadFile(fileId)
                    launch {
                        fileRepository.messageDownloadProgressFlow
                            .filter { it.first == fileId.toLong() }
                            .collect { progress = it.second }
                    }
                    fileRepository.messageDownloadCompletedFlow
                        .filter { it.first == fileId.toLong() }
                        .collect { (_, _, completedPath) -> photoPath = completedPath }
                }
            } else {
                photoPath = fileIdStr
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isPaying) onDismiss("cancelled") },
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isLoading) {
                LoadingIndicator(modifier = Modifier.padding(32.dp))
            } else if (invoice == null) {
                Text("Failed to load invoice", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onDismiss("failed") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Close") }
            } else {
                val inv = invoice!!
                if (photoPath != null) {
                    AsyncImage(
                        model = photoPath,
                        contentDescription = null,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularWavyProgressIndicator(
                            progress = { progress },
                            modifier = Modifier,
                            color = ProgressIndicatorDefaults.circularColor,
                            trackColor = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    inv.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    inv.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Total Amount", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${inv.currency} ${inv.totalAmount / 100.0}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (inv.isTest) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "TEST PAYMENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = {
                        isPaying = true
                        scope.launch {
                            val success =
                                paymentRepository.payInvoice(slug = slug, chatId = chatId, messageId = messageId)
                            isPaying = false
                            if (success) {
                                onDismiss("paid")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !isPaying,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isPaying) {
                        LoadingIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            "Pay ${inv.currency} ${inv.totalAmount / 100.0}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { onDismiss("cancelled") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !isPaying,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
