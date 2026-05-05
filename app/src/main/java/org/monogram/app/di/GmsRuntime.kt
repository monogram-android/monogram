package org.monogram.app.di

interface GmsRuntime {
    val isGmsAvailable: Boolean
    val isFcmConfigured: Boolean
}
