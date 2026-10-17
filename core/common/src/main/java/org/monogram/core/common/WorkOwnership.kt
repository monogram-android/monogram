package org.monogram.core.common

/**
 * One registry for shared work. HTTP transfers and the selected-target warmup
 * acquire, release, promote, and finish under the same lock.
 */
object WorkOwnership {
    data class State(val consumers: Int, val priority: Int, val terminal: Boolean)

    private class Entry(var consumers: Int, var priority: Int, var terminal: Boolean)

    private val entries = HashMap<String, Entry>()

    fun acquire(key: String, priority: Int): State = synchronized(entries) {
        val entry = entries.getOrPut(key) { Entry(0, priority, false) }
        entry.consumers++
        if (priority > entry.priority) entry.priority = priority
        entry.state()
    }

    /** Remaining consumers, or null when this release removed the entry. */
    fun release(key: String): State? = synchronized(entries) {
        val entry = entries[key] ?: return null
        entry.consumers = (entry.consumers - 1).coerceAtLeast(0)
        if (entry.consumers == 0 && entry.terminal) entries.remove(key)
        entry.state()
    }

    fun promote(key: String, priority: Int): Int = synchronized(entries) {
        val entry = entries[key] ?: return priority
        if (priority > entry.priority) entry.priority = priority
        entry.priority
    }

    fun finish(key: String) = synchronized(entries) {
        val entry = entries[key] ?: return
        entry.terminal = true
        if (entry.consumers == 0) entries.remove(key)
    }

    fun state(key: String): State? = synchronized(entries) { entries[key]?.state() }

    private fun Entry.state() = State(consumers, priority, terminal)
}
