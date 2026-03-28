package org.monogram.presentation.features.chats.currentChat

object DownloadDebug {
    const val TAG = "DownloadDebug"

    fun shortStack(skipClassesContaining: String = "DefaultChatComponent", maxFrames: Int = 5): String {
        return Throwable().stackTrace
            .asSequence()
            .filterNot { it.className.contains(skipClassesContaining) }
            .take(maxFrames)
            .joinToString(" <- ") { "${it.fileName}:${it.lineNumber}#${it.methodName}" }
    }
}
