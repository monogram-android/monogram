package org.monogram.presentation.core.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.monogram.domain.repository.EditorSnippet
import org.monogram.domain.repository.EditorSnippetProvider

class EditorSnippetPreferences(context: Context) : EditorSnippetProvider {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _snippets = MutableStateFlow(loadFromPrefs())
    override val snippets: StateFlow<List<EditorSnippet>> = _snippets

    override fun save(snippets: List<EditorSnippet>) {
        val json = JSONArray()
        snippets.forEach { snippet ->
            json.put(
                JSONObject().apply {
                    put("title", snippet.title)
                    put("text", snippet.text)
                }
            )
        }
        prefs.edit().putString(KEY_SNIPPETS, json.toString()).apply()
        _snippets.value = snippets
    }

    private fun loadFromPrefs(): List<EditorSnippet> {
        val raw = prefs.getString(KEY_SNIPPETS, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    val title = item.optString("title")
                    val text = item.optString("text")
                    if (title.isNotBlank() && text.isNotBlank()) {
                        add(EditorSnippet(title = title, text = text))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        private const val PREFS_NAME = "fullscreen_editor_features"
        private const val KEY_SNIPPETS = "snippets"
    }
}
