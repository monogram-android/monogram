package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow

data class EditorSnippet(
    val title: String,
    val text: String
)

interface EditorSnippetProvider {
    val snippets: StateFlow<List<EditorSnippet>>

    fun save(snippets: List<EditorSnippet>)
}
