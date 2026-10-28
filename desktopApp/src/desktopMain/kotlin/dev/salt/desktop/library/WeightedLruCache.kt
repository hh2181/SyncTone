package dev.salt.desktop.library

internal class WeightedLruCache<K, V>(private val maximumWeight: Long, private val weigh: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>(16, .75f, true)
    var weight = 0L
        private set

    @Synchronized operator fun get(key: K): V? = entries[key]

    @Synchronized fun put(key: K, value: V) {
        entries.remove(key)?.let { weight -= weigh(it) }
        val size = weigh(value)
        if (size > maximumWeight) return
        entries[key] = value
        weight += size
        val iterator = entries.iterator()
        while (weight > maximumWeight && iterator.hasNext()) {
            weight -= weigh(iterator.next().value)
            iterator.remove()
        }
    }

    @Synchronized fun remove(key: K) { entries.remove(key)?.let { weight -= weigh(it) } }
    @Synchronized fun clear() { entries.clear(); weight = 0 }
}
