package org.monogram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.defaultComponentContext

/** Isolated, non-exported host for recreation tests without a live Telegram account. */
class UiStateTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val context = defaultComponentContext()
        setContent { content(context) }
    }

    companion object {
        var content: @Composable (ComponentContext) -> Unit = {}
    }
}
