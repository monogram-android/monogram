package org.monogram.domain.managers

interface DistrManager {
    fun isGmsAvailable(): Boolean
    fun isFcmAvailable(): Boolean
    fun isInstalledFromGooglePlay(): Boolean
}