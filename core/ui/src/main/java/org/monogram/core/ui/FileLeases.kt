package org.monogram.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.io.File

/**
 * Keeps a cached media file from being trimmed or cleared while a consumer reads it.
 * The media layer installs the leaser; without one (tests, previews) nothing is held.
 */
object FileLeases {
    @Volatile
    var leaser: ((File) -> AutoCloseable?)? = null

    fun acquire(file: File): AutoCloseable? = leaser?.invoke(file)

    inline fun <T> hold(file: File, block: () -> T): T {
        val lease = acquire(file)
        try {
            return block()
        } finally {
            lease?.close()
        }
    }
}

/** Holds a lease on [file] for as long as the caller stays in composition with it. */
@Composable
fun rememberFileLease(file: File?) {
    DisposableEffect(file?.absolutePath) {
        val lease = file?.let(FileLeases::acquire)
        onDispose { lease?.close() }
    }
}
