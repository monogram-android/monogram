package org.monogram.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> collectWhenActive(flow: StateFlow<T>, active: Boolean): T {
    val held = remember(flow) { mutableStateOf(flow.value) }
    LaunchedEffect(flow, active) {
        if (!active) return@LaunchedEffect
        flow.collect { held.value = it }
    }
    return held.value
}