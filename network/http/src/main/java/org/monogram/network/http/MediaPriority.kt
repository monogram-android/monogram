package org.monogram.network.http

/**
 * Maps Telegram Android FileLoader onto this queue:
 * PRIORITY_LOW -> [IDLE], PRIORITY_NORMAL -> [DEFAULT],
 * PRIORITY_HIGH -> [VISIBLE], stripped/cached JPEG -> [THUMB],
 * force/tap -> [USER].
 * Worker cap matches MessagesController.smallQueueMaxActiveOperations (5).
 */
object MediaPriority {
    const val IDLE = 0
    const val DEFAULT = 10
    const val VISIBLE = 20
    const val THUMB = 25
    const val USER = 30

    fun isBackground(priority: Int): Boolean = priority <= IDLE
}
