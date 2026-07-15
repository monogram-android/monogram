package org.monogram.presentation.core.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.monogram.domain.repository.MessageDisplayer

class ToastMessageDisplayer(
    private val context: Context
) : MessageDisplayer {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun show(message: String) {
        val showToast = {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToast()
        } else {
            mainHandler.post(showToast)
        }
    }
}