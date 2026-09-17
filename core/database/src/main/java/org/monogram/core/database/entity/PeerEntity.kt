package org.monogram.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val id: Long,
    val kind: String,
    val title: String,
    val username: String? = null,
    val about: String? = null,
    val avatarCacheKey: String? = null,
    val status: String? = null,
    val statusAt: Long? = null,
    val emojiStatusDocumentId: Long? = null,
    val extraJson: String? = null,
    val isSelf: Boolean = false,
)
