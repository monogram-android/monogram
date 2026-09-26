package org.monogram

import androidx.activity.ComponentActivity

/**
 * Debug-only, empty activity that declares `supportsPictureInPicture`, so instrumented
 * tests can drive a real PiP transition with their own Compose content. Release builds
 * do not include it.
 */
class PipTestActivity : ComponentActivity()
