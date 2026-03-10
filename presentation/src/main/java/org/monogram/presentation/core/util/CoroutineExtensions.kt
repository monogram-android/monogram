package org.monogram.presentation.core.util

import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleOwner
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

fun Lifecycle.coroutineScope(context: CoroutineContext = Dispatchers.Main.immediate): CoroutineScope {
    val scope = CoroutineScope(context + SupervisorJob())
    doOnDestroy(scope::cancel)
    return scope
}

val LifecycleOwner.componentScope: CoroutineScope
    get() = lifecycle.coroutineScope()
