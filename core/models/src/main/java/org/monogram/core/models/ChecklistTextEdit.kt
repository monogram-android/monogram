package org.monogram.core.models

data class ChecklistTextEdit(
    val itemId: Int?,
    val text: String,
    val entities: List<TextEntity>,
    val expectedText: String,
    val expectedEntities: List<TextEntity>,
)
