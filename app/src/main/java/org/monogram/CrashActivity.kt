package org.monogram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.theme.MonogramTheme
import kotlin.system.exitProcess

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crashLog = intent?.getStringExtra(EXTRA_CRASH_LOG).orEmpty()
        setContent {
            MonogramTheme {
                CrashScreen(
                    log = crashLog,
                    onCopy = { copyLog(crashLog) },
                    onShare = { shareLog(crashLog) },
                    onRestart = { restartApp() },
                )
            }
        }
    }

    private fun copyLog(log: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_log_label), log))
        Toast.makeText(this, getString(R.string.crash_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareLog(log: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, log)
        }
        startActivity(Intent.createChooser(send, getString(R.string.crash_share_title)))
    }

    private fun restartApp() {
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let(::startActivity)
        finish()
        exitProcess(0)
    }

    companion object {
        const val EXTRA_CRASH_LOG = "EXTRA_CRASH_LOG"

        fun intent(context: Context, log: String): Intent =
            Intent(context, CrashActivity::class.java)
                .putExtra(EXTRA_CRASH_LOG, log)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashScreen(
    log: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRestart: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.crash_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.crash_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                SelectionContainer {
                    Text(
                        text = log,
                        modifier = Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.crash_copy))
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.crash_share))
            }
            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.crash_restart))
            }
        }
    }
}
