package org.monogram.presentation.settings.debug

interface DebugComponent {
    fun onBackClicked()
    fun onCrashClicked()
    fun onDropDatabasesClicked()
    fun onDropCachePrefsClicked()
    fun onDropPrefsClicked()
    fun onDropDatabaseCacheClicked()
}