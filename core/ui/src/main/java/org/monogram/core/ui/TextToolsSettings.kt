package org.monogram.core.ui

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** Shared translation preferences; text and server results are never persisted here. */
object TextToolsSettings {
    private var preferences: SharedPreferences? = null

    fun install(context: Context) {
        if (preferences == null) {
            preferences =
                context.applicationContext.getSharedPreferences("text_tools", Context.MODE_PRIVATE)
        }
    }

    fun manualTranslation(): Boolean = preferences?.getBoolean("manual", true) ?: true

    fun chatTranslation(): Boolean = preferences?.getBoolean("chat", true) ?: true

    fun excludedLanguages(): Set<String> =
        preferences?.getStringSet("excluded", setOf(Locale.getDefault().language))?.toSet()
            ?: setOf(Locale.getDefault().language)

    fun setGlobal(
        manual: Boolean,
        chat: Boolean,
        excluded: Set<String>,
    ) {
        preferences
            ?.edit()
            ?.putBoolean("manual", manual)
            ?.putBoolean("chat", chat)
            ?.putStringSet("excluded", excluded.toSet())
            ?.apply()
    }

    fun targetLanguage(
        account: Long,
        peer: Long,
    ): String =
        preferences?.getString("$account:$peer:language", null) ?: Locale.getDefault().language

    fun chatEnabled(
        account: Long,
        peer: Long,
    ): Boolean = preferences?.getBoolean("$account:$peer:enabled", false) ?: false

    fun setDialog(
        account: Long,
        peer: Long,
        language: String,
        enabled: Boolean,
    ) {
        preferences
            ?.edit()
            ?.putString("$account:$peer:language", language)
            ?.putBoolean("$account:$peer:enabled", enabled)
            ?.apply()
    }
}
