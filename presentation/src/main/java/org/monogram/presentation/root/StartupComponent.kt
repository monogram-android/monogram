package org.monogram.presentation.root

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.presentation.R

interface StartupComponent {
    val connectionStatus: StateFlow<ConnectionStatus>
}

class DefaultStartupComponent(
    context: AppComponentContext
) : StartupComponent, AppComponentContext by context {
    override val connectionStatus: StateFlow<ConnectionStatus>
        get() = container.repositories.chatListRepository.connectionStateFlow
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StartupContent(component: StartupComponent) {
    val connectionStatus by component.connectionStatus.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = ShapeDefaults.ExtraLargeIncreased,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_app_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.app_name_monogram),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                AnimatedContent(
                    targetState = connectionStatus,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "StartupStatus"
                ) { status ->
                    Text(
                        text = when (status) {
                            ConnectionStatus.WaitingForNetwork -> stringResource(R.string.waiting_for_network)
                            ConnectionStatus.Connecting -> stringResource(R.string.connecting)
                            ConnectionStatus.Updating -> stringResource(R.string.updating)
                            ConnectionStatus.ConnectingToProxy -> stringResource(R.string.connecting_to_proxy)
                            ConnectionStatus.Connected -> stringResource(R.string.startup_connecting)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                LinearWavyProgressIndicator(
                    modifier = Modifier.width(220.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}