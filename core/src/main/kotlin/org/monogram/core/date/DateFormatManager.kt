package org.monogram.core.date

interface DateFormatManager {
    fun is24HourFormat(): Boolean
    fun getHourMinuteFormat(): String
}

class DateFormatManagerImpl(
    private val use24HourFormat: Boolean
) : DateFormatManager {
    override fun is24HourFormat(): Boolean = use24HourFormat
    override fun getHourMinuteFormat(): String = if (use24HourFormat) "HH:mm" else "h:mm a"
}

class Fake12HourDateFormatManagerImpl : DateFormatManager {
    override fun is24HourFormat(): Boolean = false
    override fun getHourMinuteFormat(): String = "h:mm a"
}

class Fake24HourDateFormatManagerImpl : DateFormatManager {
    override fun is24HourFormat(): Boolean = true
    override fun getHourMinuteFormat(): String = "HH:mm"
}
