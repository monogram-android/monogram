package org.monogram.app.di

class LibreGmsRuntime : GmsRuntime {
    override val isGmsAvailable: Boolean = false
    override val isFcmConfigured: Boolean = false
}
