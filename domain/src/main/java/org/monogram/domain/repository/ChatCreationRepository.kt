package org.monogram.domain.repository

interface ChatCreationRepository {
    suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int = 0): Long

    suspend fun createChannel(
        title: String,
        description: String,
        isMegagroup: Boolean = false,
        messageAutoDeleteTime: Int = 0
    ): Long

    fun getDatabaseSize(): Long
    fun clearDatabase()
}