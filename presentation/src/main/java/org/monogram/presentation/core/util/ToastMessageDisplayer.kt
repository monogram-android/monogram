package org.monogram.presentation.core.util

import android.content.Context
import android.widget.Toast
import org.monogram.domain.repository.MessageDisplayer

class ToastMessageDisplayer(
    private val context: Context
) : MessageDisplayer {
    override fun show(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}