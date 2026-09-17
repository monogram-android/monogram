package org.monogram.core.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.monogram.core.models.LoadingUi
import org.monogram.core.models.LoadingUi.Companion.Idle

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
fun MonogramLoadingHarnessPreview() {
    MaterialTheme {
        MonogramLoadingHarness()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonogramLoadingHarness() {
    var loading by remember { mutableStateOf<LoadingUi>(Idle) }
    var counted by remember { mutableStateOf<LoadingUi>(Idle) }
    var refresh by remember { mutableStateOf<LoadingUi>(Idle) }
    var formBusy by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf(false) }
    var fieldBusy by remember { mutableStateOf(false) }
    var fieldValue by remember { mutableStateOf("") }
    var lateResultAttempts by remember { mutableIntStateOf(0) }

    LaunchedEffect(counted.visible, counted.generation) {
        val generation = counted.generation
        if (!counted.visible || counted.progress == null) return@LaunchedEffect
        while (true) {
            delay(180)
            if (!counted.isCurrent(generation)) return@LaunchedEffect
            counted = counted.tick(counted.fraction + 0.1f)
            if (counted.fraction >= 1f) return@LaunchedEffect
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MonogramLoading harness") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Screen-level bar (thin wavy)", style = MaterialTheme.typography.titleSmall)
                    MonogramLinearProgress(
                        visible = loading.visible,
                        progress = if (loading.progress != null) ({ loading.fraction }) else null,
                        generation = loading.generation,
                        status = "Loading",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { loading = loading.start() }) { Text("Start") }
                        Button(onClick = { loading = loading.stop() }) { Text("Stop") }
                        Button(onClick = { loading = loading.stop().start() }) { Text("Restart") }
                        Button(onClick = { loading = loading.startDeterminate() }) { Text("Counted") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hero contained morph", style = MaterialTheme.typography.titleSmall)
                    MonogramProgressSettled(
                        progress = if (counted.progress != null) ({ counted.fraction }) else null,
                        generation = counted.generation,
                    ) { counted = counted.stop() }
                    MonogramLoadingOverlay(
                        visible = counted.visible && counted.progress == null,
                        generation = counted.generation,
                        status = "Working",
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            MonogramLoadingContained(
                                visible = counted.visible && counted.progress != null,
                                progress = if (counted.progress != null) ({ counted.fraction }) else null,
                                generation = counted.generation,
                                status = "Uploading",
                            )
                        }
                    }
                    MonogramCircularProgress(
                        visible = counted.visible,
                        progress = if (counted.progress != null) ({ counted.fraction }) else null,
                        generation = counted.generation,
                        size = 48.dp,
                        status = "Media transfer",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { counted = counted.startDeterminate() }) { Text("Start counted") }
                        Button(onClick = { counted = counted.fail() }) { Text("Fail") }
                        Button(onClick = { counted = counted.retry() }) { Text("Retry") }
                        Button(onClick = { counted = counted.stop() }) { Text("Stop") }
                    }
                    if (counted.error) {
                        Text("Error affordance shown; retry restarts from 0", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cancel + late result", style = MaterialTheme.typography.titleSmall)
                    MonogramLoading(visible = refresh.visible, generation = refresh.generation, status = "Working")
                    Text("late results ignored: $lateResultAttempts")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            refresh = refresh.start()
                            val generation = refresh.generation
                            if (!refresh.isCurrent(generation)) lateResultAttempts++
                            refresh = refresh.cancel()
                            if (!refresh.isCurrent(generation)) lateResultAttempts++
                        }) { Text("Start then cancel") }
                        Button(onClick = { refresh = Idle }) { Text("Reset") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pull to refresh", style = MaterialTheme.typography.titleSmall)
                    Text("Pull down inside the box below.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { refresh = refresh.start() }) { Text("Simulate refresh") }
                        Button(onClick = { refresh = refresh.stop() }) { Text("End refresh") }
                    }
                }
            }
            MonogramRefreshBox(
                isRefreshing = refresh.visible,
                onRefresh = { refresh = if (refresh.visible) refresh else refresh.start() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    repeat(6) { index ->
                        Text("Row $index", modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Form", style = MaterialTheme.typography.titleSmall)
                    MonogramLinearProgress(
                        visible = formBusy,
                        generation = formBusy.hashCode(),
                        status = "Submitting",
                    )
                    OutlinedTextField(
                        value = "alice",
                        onValueChange = {},
                        enabled = !formBusy,
                        label = { Text("Username") },
                        isError = formError,
                        supportingText = if (formError) ({ Text("Already taken") }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MonogramBusyField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        busy = fieldBusy,
                        label = "Check username",
                        status = "Checking username",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { fieldBusy = !fieldBusy }) { Text("Toggle field check") }
                    }
                    MonogramBusyButton(
                        text = "Submit",
                        busy = formBusy,
                        onClick = {
                            formError = false
                            formBusy = true
                        },
                    )
                    Button(
                        onClick = {
                            formBusy = false
                            formError = true
                        },
                    ) { Text("Fail submit") }
                }
            }
        }
    }
}
