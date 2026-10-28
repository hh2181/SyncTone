package dev.salt.desktop.lyrics

/** A composition owns the result even if its background producer is cancelled during handoff. */
internal class OwnedResource<T : AutoCloseable> : AutoCloseable {
    private var closed = false
    private var value: T? = null

    @Synchronized fun install(resource: T): T? {
        if (closed) { resource.close(); return null }
        check(value == null)
        value = resource
        return resource
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        value?.close()
        value = null
    }
}
