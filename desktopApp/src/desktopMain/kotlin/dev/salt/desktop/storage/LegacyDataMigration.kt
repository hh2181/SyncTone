package dev.salt.desktop.storage

import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

internal object LegacyDataMigration {
    fun copyIfEmpty(legacy: Path, target: Path) {
        if (!Files.isDirectory(legacy) || legacy.toAbsolutePath().normalize() == target.toAbsolutePath().normalize()) return
        if (Files.exists(target) && (!Files.isDirectory(target) || Files.list(target).use { it.findAny().isPresent })) return
        if (!listOf("session.json", "library.db", "playback-statistics.db", "sync-state.json", "synced-music")
                .any { Files.exists(legacy.resolve(it)) }) return
        if (listOf("library.db-wal", "playback-statistics.db-wal").any { Files.exists(legacy.resolve(it)) }) {
            throw IOException("旧版数据库尚未关闭，请打开旧版 SyncTone 并正常退出后重试迁移。")
        }

        Files.createDirectories(target.toAbsolutePath().parent)
        val staging = Files.createTempDirectory(target.toAbsolutePath().parent, "data-migration-")
        val sourceAttributes = mutableMapOf<Path, Pair<Long, java.nio.file.attribute.FileTime>>()
        try {
            Files.walkFileTree(legacy, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(staging.resolve(legacy.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) throw IOException("Cannot migrate non-regular data file: $file")
                    // Refuse a changing source database instead of publishing a partial copy.
                    val destination = staging.resolve(legacy.relativize(file))
                    sourceAttributes[file] = attrs.size() to attrs.lastModifiedTime()
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    val after = Files.readAttributes(file, BasicFileAttributes::class.java)
                    if (attrs.size() != after.size() || attrs.lastModifiedTime() != after.lastModifiedTime()) {
                        throw IOException("旧版数据正在使用中，请关闭旧版 SyncTone 后重试：$file")
                    }
                    return FileVisitResult.CONTINUE
                }
            })
            val afterCopy = Files.walk(legacy).use { paths -> paths.filter { !Files.isDirectory(it) }.toList().associateWith {
                val attrs = Files.readAttributes(it, BasicFileAttributes::class.java)
                attrs.size() to attrs.lastModifiedTime()
            } }
            if (sourceAttributes != afterCopy) throw IOException("旧版数据发生变化，请关闭旧版 SyncTone 后重试迁移。")
            // Only an absent/empty target is replaced, after every file was successfully copied.
            Files.deleteIfExists(target)
            try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(staging, target) }
        } finally {
            if (Files.exists(staging)) Files.walkFileTree(staging, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    file.toFile().setWritable(true)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
