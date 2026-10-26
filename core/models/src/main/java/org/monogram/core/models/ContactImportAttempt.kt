package org.monogram.core.models

enum class ContactImportPhase { IN_FLIGHT, UNCERTAIN, RETRY_REQUIRED, IMPORTED, NOT_ADDED }

data class ContactImportAttempt(
    val spec: ContactImportSpec,
    val phase: ContactImportPhase,
    val userId: PeerId? = null,
)
