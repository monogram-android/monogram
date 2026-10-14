package org.monogram.core.common

/**
 * Optional signals installed by the process. Unset probes do not block work.
 * Thermal, radio, and database probes are read by the selected-target warmup.
 */
object RuntimePressure {
    @Volatile
    var networkConstrained: () -> Boolean = { false }

    @Volatile
    var thermal: () -> Boolean = { false }

    @Volatile
    var databaseBusy: () -> Boolean = { false }

    fun blocked(): Boolean = networkConstrained() || thermal() || databaseBusy()
}
