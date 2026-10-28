package dev.salt.desktop.sync

import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

internal class SyncPreferences(private val directory: Path) {
    private val file = directory.resolve("sync-state.json")
    private var values = if (Files.exists(file)) JSONObject(Files.readString(file)) else JSONObject()

    @Synchronized fun get(key: String): String? = if (values.has(key)) values.getString(key) else null

    @Synchronized fun edit(vararg entries: Pair<String, String?>) {
        val next = JSONObject(values.toString())
        entries.forEach { (key, value) -> if (value == null) next.remove(key) else next.put(key, value) }
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "sync-state-", ".tmp")
        try {
            Files.writeString(temporary, next.toString())
            try { Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file, REPLACE_EXISTING) }
            values = next
        } finally { Files.deleteIfExists(temporary) }
    }
}
