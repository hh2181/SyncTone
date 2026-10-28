package dev.salt.desktop

import dev.salt.desktop.storage.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.*

class PortableStorageTests {
    @Test fun developmentAndExplicitDirectoriesNeverDefaultToTheUserProfile() {
        val root = Files.createTempDirectory("synctone-paths")
        val keys = listOf("user.dir", "synctone.data.dir", "compose.application.resources.dir")
        val previous = keys.associateWith(System::getProperty)
        try {
            System.setProperty("user.dir", root.toString())
            System.clearProperty("compose.application.resources.dir")
            System.clearProperty("synctone.data.dir")
            assertEquals(root.resolve("data"), DesktopPaths.dataDirectory())
            System.setProperty("synctone.data.dir", root.resolve("isolated").toString())
            assertEquals(root.resolve("isolated"), DesktopPaths.dataDirectory())
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            root.toFile().deleteRecursively()
        }
    }

    @Test fun migrationCopiesTheWholeLegacyDirectoryAndLeavesTheSourceUntouched() {
        val root = Files.createTempDirectory("synctone-migration")
        try {
            val legacy = root.resolve("legacy")
            val target = root.resolve("portable/data")
            val saved = DesktopSession(positionMs = 12345, volume = 37)
            DesktopSessionRepository(legacy).write(saved)
            Files.writeString(legacy.resolve("library.db"), "database fixture")
            Files.createDirectories(legacy.resolve("synced-music"))
            Files.writeString(legacy.resolve("synced-music/song.mp3"), "audio fixture")
            val sourceSession = Files.readString(legacy.resolve("session.json"))
            val repository = DesktopSessionRepository(target, legacyDirectory = legacy)
            assertEquals(saved, repository.read())
            assertEquals("audio fixture", Files.readString(target.resolve("synced-music/song.mp3")))
            repository.write(saved.copy(volume = 80))
            assertEquals(sourceSession, Files.readString(legacy.resolve("session.json")))
            assertEquals("database fixture", Files.readString(legacy.resolve("library.db")))
            Files.list(target.parent).use { assertEquals(listOf("data"), it.map { path -> path.fileName.toString() }.toList()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun existingPortableDataTakesPriorityAndNeverMergesOldAccounts() {
        val root = Files.createTempDirectory("synctone-existing")
        try {
            val legacy = root.resolve("legacy")
            val target = root.resolve("portable")
            DesktopSessionRepository(legacy).write(DesktopSession(volume = 12))
            DesktopSessionRepository(target).write(DesktopSession(volume = 65))
            Files.writeString(legacy.resolve("sync-state.json"), "old trust")
            assertEquals(65, DesktopSessionRepository(target, legacyDirectory = legacy).read().volume)
            assertFalse(Files.exists(target.resolve("sync-state.json")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unclosedLegacyDatabaseIsPreservedAndCanBeMigratedAfterClosing() {
        val root = Files.createTempDirectory("synctone-unclosed")
        try {
            val legacy = root.resolve("legacy")
            val target = root.resolve("portable/data")
            DesktopSessionRepository(legacy).write(DesktopSession(volume = 45))
            Files.writeString(legacy.resolve("library.db-wal"), "uncheckpointed data")
            val repository = DesktopSessionRepository(target, legacyDirectory = legacy)
            assertFailsWith<java.io.IOException> { repository.read() }
            assertFalse(Files.exists(target))
            assertEquals("uncheckpointed data", Files.readString(legacy.resolve("library.db-wal")))
            Files.delete(legacy.resolve("library.db-wal"))
            assertEquals(45, repository.read().volume)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun blockedPortableStorageReportsFailureWithoutWritingIntoLegacyStorage() {
        val root = Files.createTempDirectory("synctone-readonly")
        try {
            val legacy = root.resolve("legacy")
            DesktopSessionRepository(legacy).write(DesktopSession(volume = 33))
            val target = root.resolve("data")
            Files.writeString(target, "occupied")
            val error = assertFailsWith<java.io.IOException> {
                DesktopSessionRepository(target, legacyDirectory = legacy).write(DesktopSession())
            }
            assertTrue(error.message!!.contains(target.toString()))
            assertEquals(33, DesktopSessionRepository(legacy).read().volume)
            assertEquals("occupied", Files.readString(target))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun syncedMusicKeepsItsIdentityAfterMovingThePortableDataDirectory() {
        val root = Files.createTempDirectory("synctone-moved")
        try {
            val original = root.resolve("before/data")
            val music = Files.createDirectories(original.resolve("synced-music"))
            val song = Files.copy(Path.of(System.getProperty("synctone.reference.path")), music.resolve("song.mp3"))
            val id = DesktopLibraryRepository(original).use { it.register(song).id }
            val moved = root.resolve("after/data")
            Files.createDirectories(moved.parent)
            Files.move(original, moved, StandardCopyOption.ATOMIC_MOVE)
            DesktopLibraryRepository(moved).use { repository ->
                repository.refreshAvailability()
                val track = repository.tracks().single()
                assertEquals(id, track.id)
                assertTrue(track.available)
                assertEquals(moved.resolve("synced-music/song.mp3").toRealPath().toString().lowercase(),
                    track.path.toRealPath().toString().lowercase())
                assertNull(repository.idForPath(song.toString()))
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
