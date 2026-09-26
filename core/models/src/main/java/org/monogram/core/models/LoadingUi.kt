package org.monogram.core.models

data class LoadingUi(
    val visible: Boolean = false,
    val progress: Float? = null,
    val error: Boolean = false,
    val generation: Int = 0,
) {
    val determinate: Boolean get() = progress != null

    val fraction: Float get() = (progress ?: 0f).coerceIn(0f, 1f)

    fun start(): LoadingUi = copy(
        visible = true,
        progress = null,
        error = false,
        generation = generation + 1,
    )

    fun startDeterminate(): LoadingUi = copy(
        visible = true,
        progress = 0f,
        error = false,
        generation = generation + 1,
    )

    fun tick(fraction: Float): LoadingUi = copy(
        visible = true,
        progress = fraction.coerceIn(0f, 1f),
        error = false,
    )

    fun complete(): LoadingUi = tick(1f)

    fun stop(): LoadingUi = copy(visible = false, progress = null, error = false)

    fun fail(): LoadingUi = copy(visible = false, error = true)

    fun cancel(): LoadingUi = stop()

    fun dismissError(): LoadingUi = copy(error = false)

    fun retry(): LoadingUi = copy(
        visible = true,
        progress = if (progress != null) 0f else null,
        error = false,
        generation = generation + 1,
    )

    fun isCurrent(generation: Int): Boolean = visible && this.generation == generation

    companion object {
        val Idle: LoadingUi = LoadingUi()

        fun flag(
            visible: Boolean,
            generation: Int,
            error: Boolean = false,
        ): LoadingUi = LoadingUi(visible = visible, error = error, generation = generation)
    }
}
