package org.monogram.presentation.core.ui


import android.os.Bundle
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import org.monogram.presentation.core.util.ScrollStrategy


@Stable
class CollapsingToolbarScaffoldState(
    val toolbarState: CollapsingToolbarState,
    initialOffsetY: Int = 0
) {
    val offsetY: Int
        get() = offsetYState.value

    internal val offsetYState = mutableStateOf(initialOffsetY)
}

private class CollapsingToolbarScaffoldStateSaver: Saver<CollapsingToolbarScaffoldState, Bundle> {
    override fun restore(value: Bundle): CollapsingToolbarScaffoldState =
        CollapsingToolbarScaffoldState(
            CollapsingToolbarState(value.getInt("height", Int.MAX_VALUE)),
            value.getInt("offsetY", 0)
        )

    override fun SaverScope.save(value: CollapsingToolbarScaffoldState): Bundle =
        Bundle().apply {
            putInt("height", value.toolbarState.height)
            putInt("offsetY", value.offsetY)
        }
}

@Composable
fun rememberCollapsingToolbarScaffoldState(
    toolbarState: CollapsingToolbarState = rememberCollapsingToolbarState()
): CollapsingToolbarScaffoldState {
    return rememberSaveable(toolbarState, saver = CollapsingToolbarScaffoldStateSaver()) {
        CollapsingToolbarScaffoldState(toolbarState)
    }
}

@Composable
fun CollapsingToolbarScaffold(
    modifier: Modifier,
    state: CollapsingToolbarScaffoldState,
    scrollStrategy: ScrollStrategy,
    enabled: Boolean = true,
    toolbarModifier: Modifier = Modifier,
    toolbar: @Composable CollapsingToolbarScope.() -> Unit,
    body: @Composable () -> Unit
) {
    val flingBehavior = ScrollableDefaults.flingBehavior()

    val nestedScrollConnection = remember(scrollStrategy, state) {
        scrollStrategy.create(state.offsetYState, state.toolbarState, flingBehavior)
    }

    val toolbarState = state.toolbarState

    Layout(
        content = {
            CollapsingToolbar(
                modifier = toolbarModifier,
                collapsingToolbarState = toolbarState
            ) {
                toolbar()
            }
            body()
        },
        modifier = modifier
            .then(
                if (enabled) {
                    Modifier.nestedScroll(nestedScrollConnection)
                } else {
                    Modifier
                }
            )
    ) { measurables, constraints ->
        val toolbarConstraints = constraints.copy(
            minWidth = 0,
            minHeight = 0
        )
        val bodyConstraints = constraints.copy(
            minWidth = 0,
            minHeight = 0,
            maxHeight = when (scrollStrategy) {
                ScrollStrategy.ExitUntilCollapsed ->
                    (constraints.maxHeight - toolbarState.minHeight).coerceAtLeast(0)

                ScrollStrategy.EnterAlways, ScrollStrategy.EnterAlwaysCollapsed ->
                    constraints.maxHeight
            }
        )

        val toolbarPlaceable = measurables[0].measure(toolbarConstraints)
        val bodyPlaceables =
            measurables.drop(1).map { it.measure(bodyConstraints) }

        val toolbarHeight = toolbarPlaceable.height

        val width = constraints.maxWidth
        val height = constraints.maxHeight

        layout(width, height) {
            bodyPlaceables.forEach {
                it.place(0, toolbarHeight + state.offsetY)
            }
            toolbarPlaceable.place(0, state.offsetY)
        }
    }
}
