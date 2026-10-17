package org.monogram

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets

class BubbleActivity : MainActivity() {
    private var contentView: View? = null
    private val fitVisibleDisplay = ViewTreeObserver.OnGlobalLayoutListener {
        val content = contentView ?: return@OnGlobalLayoutListener
        if (Build.VERSION.SDK_INT < 30) return@OnGlobalLayoutListener
        val display = windowManager.maximumWindowMetrics
        val systemBars = display.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val location = IntArray(2)
        content.getLocationOnScreen(location)
        // Some SystemUI bubble tasks extend below the display after switching conversations.
        val bottom = (location[1] + content.height - display.bounds.bottom + systemBars.bottom)
            .coerceAtLeast(0)
        if (content.paddingBottom != bottom) {
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contentView = findViewById(android.R.id.content)
        contentView?.viewTreeObserver?.addOnGlobalLayoutListener(fitVisibleDisplay)
    }

    override fun onDestroy() {
        contentView?.viewTreeObserver?.removeOnGlobalLayoutListener(fitVisibleDisplay)
        contentView = null
        super.onDestroy()
    }
}
