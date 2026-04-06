package org.monogram.data.infra

class SynchronizedLruMap<K, V>(
    private val maxSize: Int
) {
    init {
        require(maxSize > 0) { "maxSize must be > 0" }
    }

    private val lock = Any()
    private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    operator fun get(key: K): V? {
        return synchronized(lock) { map[key] }
    }

    operator fun set(key: K, value: V) {
        synchronized(lock) {
            map[key] = value
        }
    }

    fun containsKey(key: K): Boolean {
        return synchronized(lock) { map.containsKey(key) }
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }

    fun size(): Int {
        return synchronized(lock) { map.size }
    }
}
