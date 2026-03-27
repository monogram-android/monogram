package org.monogram.presentation.settings.debug

interface DebugComponent {
    fun onBackClicked()
    fun onCrashClicked()
    fun onShowSponsorSheetClicked()
    fun onForceSponsorSyncClicked()
    fun onDropDatabasesClicked()
    fun onDropCachePrefsClicked()
    fun onDropPrefsClicked()
    fun onDropDatabaseCacheClicked()
}