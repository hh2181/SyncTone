package dev.salt.desktop.storage

import java.nio.file.Files
import java.nio.file.Path

object DesktopPaths {
    fun applicationDirectory(): Path {
        // jpackage expands $APPDIR to <distribution>/app, independently of the working directory.
        System.getProperty("compose.application.resources.dir")?.let {
            val root = Path.of(it).toAbsolutePath().normalize().parent?.parent
            if (root != null && Files.isRegularFile(root.resolve("SyncTone.exe"))) return root
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    }

    fun dataDirectory(): Path = System.getProperty("synctone.data.dir")?.let(Path::of)
        ?.toAbsolutePath()?.normalize() ?: applicationDirectory().resolve("data")

    fun configureRuntime() {
        // VLC detects this literal directory beside the process executable on Windows.
        val runtime = applicationDirectory().resolve("portable")
        val temporary = Files.createDirectories(runtime.resolve("tmp")).toString()
        Files.createDirectories(runtime.resolve("logs"))
        System.setProperty("java.io.tmpdir", temporary)
        System.setProperty("jna.tmpdir", temporary)
        System.setProperty("org.sqlite.tmpdir", temporary)
    }
}
