package org.monogram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.monogram.core.harmony.HarmonyGallery

/** Debug-only host for the HarmonyOS 7 visual prototype; no Telegram account is needed. */
class HarmonyGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HarmonyGallery() }
    }
}
