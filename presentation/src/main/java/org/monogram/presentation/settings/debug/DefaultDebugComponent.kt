package org.monogram.presentation.settings.debug

import org.monogram.presentation.root.AppComponentContext
import java.io.File

class DefaultDebugComponent(
    private val context: AppComponentContext,
    private val onBack: () -> Unit
) : DebugComponent, AppComponentContext by context {

    private val messageDisplayer = container.utils.messageDisplayer()
    private val assetsManager = container.utils.assetsManager()

    override fun onBackClicked() {
        onBack()
    }

    override fun onCrashClicked() {
        throw RuntimeException("Test crash")
    }

    override fun onDropDatabasesClicked() {
        messageDisplayer.show("Dropping databases and restarting...")
        assetsManager.getDatabasePath("monogram_db").delete()
        File(assetsManager.getFilesDir(), "td-db").deleteRecursively()
        assetsManager.exitProcess(0)
    }

    override fun onDropDatabaseCacheClicked() {
        messageDisplayer.show("Dropping databases and restarting...")
        assetsManager.getDatabasePath("monogram_db").delete()
        assetsManager.exitProcess(0)
    }

    override fun onDropCachePrefsClicked() {
        messageDisplayer.show("Dropping cache prefs and restarting...")
        assetsManager.clearSharedPreferences("monogram_cache")
        assetsManager.exitProcess(0)
    }

    override fun onDropPrefsClicked() {
        messageDisplayer.show("Dropping prefs and restarting...")
        assetsManager.clearSharedPreferences("monogram_prefs")
        assetsManager.exitProcess(0)
    }
}
