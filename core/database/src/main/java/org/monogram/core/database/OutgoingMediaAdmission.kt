package org.monogram.core.database

import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.database.entity.OutgoingMediaSend

data class OutgoingMediaUpload(val path: String, val mimeType: String, val message: MessageEntity)
data class OutgoingMediaAdmission(val send: OutgoingMediaSend, val draftConsumed: Boolean)
