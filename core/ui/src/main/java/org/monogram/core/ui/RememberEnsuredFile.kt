package org.monogram.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** Reads a media cache generation at the point of use. */
@Composable
fun rememberCacheGeneration(generation: Flow<Long>?): Long =
    generation?.collectAsState(initial = 0L)?.value ?: 0L

/**
 * Resolves a local media file, then keeps it in sync when [generation] changes
 * (a download finished elsewhere, e.g. another row or prefetch).
 */
@Composable
fun rememberEnsuredFile(
    generation: Long,
    identity: Any?,
    resolve: () -> File?,
    ensure: suspend () -> File?,
): File? {
    var file by remember(identity) { mutableStateOf(resolve()) }
    LaunchedEffect(identity, generation) {
        repeat(3) { attempt ->
            val resolved = resolve()
            if (resolved != null) {
                file = resolved
                return@LaunchedEffect
            }
            val fetched = ensure()
            if (fetched != null) {
                file = fetched
                return@LaunchedEffect
            }
            if (attempt < 2) delay(400L * (attempt + 1))
        }
    }
    return file
}
