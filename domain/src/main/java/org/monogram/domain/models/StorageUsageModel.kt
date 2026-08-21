package org.monogram.domain.models

data class StorageUsageModel(
    val totalSize: Long,
    val fileCount: Int,
    val chatStats: List<ChatStorageUsageModel>
)

data class StorageUsageBreakdownModel(
    val mediaCacheSize: Long,
    val databaseSize: Long,
    val logsSize: Long,
    val languagePackDatabaseSize: Long
)

data class StorageCleanupResultModel(
    val freedSize: Long,
    val freedFileCount: Int,
    val cleanupSucceeded: Boolean
)

data class ChatStorageUsageModel(
    val chatId: Long,
    val chatTitle: String = "",
    val size: Long,
    val fileCount: Int,
    val byFileType: List<FileTypeStorageUsageModel>
)

data class FileTypeStorageUsageModel(
    val fileType: String,
    val size: Long,
    val fileCount: Int
)
