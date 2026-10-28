import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        val desktopMain by getting {
            // Compile these platform-independent files in place; Android retains ownership.
            kotlin.srcDir("../app/src/main/java")
            kotlin.include("dev/salt/desktop/**", "dev/salt/replica/lyrics/Lyrics.kt",
                "dev/salt/replica/lyrics/LyricAnimation.kt", "dev/salt/replica/lyrics/StaggerMotion.kt",
                "dev/salt/replica/lyrics/LongNoteEnvelope.kt",
                "dev/salt/replica/ui/PlayerSheetState.kt",
                "dev/salt/replica/statistics/PlaybackStatistics.kt",
                "dev/salt/replica/statistics/HistorySync.kt",
                "dev/salt/replica/sync/SyncModels.kt", "dev/salt/replica/sync/SyncLogic.kt")
            dependencies {
                implementation(project(":sharedButtons"))
                implementation(compose.desktop.currentOs)
                // Material 3 and icons have independent release cycles; keep stable artifacts.
                implementation(libs.compose.material3)
                implementation(libs.compose.icons)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("net.java.dev.jna:jna:5.15.0")
                implementation("net.jthink:jaudiotagger:3.0.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.xerial:sqlite-jdbc:3.47.1.0")
                implementation("org.json:json:20240303")
                implementation("org.nanohttpd:nanohttpd:2.3.1")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
                implementation("org.jmdns:jmdns:3.5.10")
                implementation("org.slf4j:slf4j-nop:2.0.16")
            }
        }
        val desktopTest by getting {
            kotlin.srcDir("../app/src/test/java")
            kotlin.include("dev/salt/desktop/**", "dev/salt/replica/sync/SyncLogicTest.kt")
            dependencies { implementation(kotlin("test-junit")) }
        }
    }
}

val vlcDirectory = layout.projectDirectory.dir("runtime/windows-x64/vlc")
val prepareVlc by tasks.registering(Exec::class) {
    group = "desktop"
    description = "Download and verify the pinned Windows x64 libVLC runtime."
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", project.file("tools/prepare-vlc.ps1").absolutePath)
    inputs.file("tools/prepare-vlc.ps1")
    outputs.file(vlcDirectory.file("libvlc.dll"))
    outputs.file(vlcDirectory.file("runtime.sha256"))
    outputs.cacheIf { false }
}

val prepareJna by tasks.registering(Sync::class) {
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    from(provider { zipTree(compilation.runtimeDependencyFiles!!.single { it.name.startsWith("jna-") && it.extension == "jar" }) }) {
        include("com/sun/jna/win32-x86-64/jnidispatch.dll")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.projectDirectory.dir("runtime/windows-x64/jna"))
}

val prepareSqlite by tasks.registering(Sync::class) {
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    from(provider { zipTree(compilation.runtimeDependencyFiles!!.single { it.name.startsWith("sqlite-jdbc-") }) }) {
        include("org/sqlite/native/Windows/x86_64/sqlitejdbc.dll")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.projectDirectory.dir("runtime/windows-x64/sqlite"))
}

compose.desktop {
    application {
        mainClass = "dev.salt.desktop.MainKt"
        jvmArgs += "-Dfile.encoding=UTF-8"
        // Audio and scanning use dedicated workers. Avoid oversized GC/compiler/coroutine
        // pools on high-core desktops while leaving ample parallelism for visible effects.
        jvmArgs += listOf("-Xms32m", "-Xmx512m", "-XX:ActiveProcessorCount=8", "-XX:+ExplicitGCInvokesConcurrent")
        // Keep JVM temporary files with the portable distribution. `$APPDIR` is
        // expanded by jpackage to the packaged app directory.
        jvmArgs += "-Djava.io.tmpdir=\$APPDIR/../portable/tmp"
        jvmArgs += "-Djna.tmpdir=\$APPDIR/../portable/tmp"
        jvmArgs += "-Dorg.sqlite.tmpdir=\$APPDIR/../portable/tmp"
        jvmArgs += "-XX:-UsePerfData"
        jvmArgs += "-XX:ErrorFile=\$APPDIR/../portable/logs/hs_err_pid%p.log"
        jvmArgs += "-XX:HeapDumpPath=\$APPDIR/../portable/logs"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "SyncTone"
            packageVersion = "0.4.11"
            modules("java.desktop", "java.logging", "java.net.http", "jdk.unsupported", "java.sql", "jdk.crypto.ec")
            appResourcesRootDir.set(layout.projectDirectory.dir("runtime"))
            windows {
                menuGroup = "SyncTone"
                shortcut = true
                upgradeUuid = "1b3c40a2-2ee8-4ff3-b8d1-9106ac47b925"
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    dependsOn(prepareVlc)
    systemProperty("synctone.vlc.path", vlcDirectory.asFile.absolutePath)
    doFirst {
        // The development runner has no jpackage launcher to expand its macro.
        jvmArgs = jvmArgs.map { it.replace("\$APPDIR", rootProject.file("app").absolutePath) }
        rootProject.file("portable/tmp").mkdirs()
        rootProject.file("portable/logs").mkdirs()
    }
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareVlc)
    systemProperty("synctone.vlc.path", vlcDirectory.asFile.absolutePath)
    systemProperty("synctone.reference.path", rootProject.file("app/src/main/assets/reference.mp3").absolutePath)
    testLogging { events("passed", "skipped", "failed") }
}
tasks.named<Copy>("desktopProcessResources") {
    from(rootProject.file("app/src/main/res/drawable")) {
        include("ic_round_*.xml", "ic_player_*.xml", "ic_sleep_timer.xml", "ic_audio_effect.xml",
            "ic_play_queue.xml", "ic_translation.xml", "ic_media_router.xml", "ic_nav_*.xml")
        into("icons")
    }
}
tasks.matching { it.name == "prepareAppResources" || it.name == "createDistributable" || it.name.startsWith("package") }.configureEach {
    dependsOn(prepareVlc, prepareJna, prepareSqlite)
}

val portableApplication = layout.buildDirectory.dir("compose/binaries/main/app/SyncTone")
val preservedPortableData = layout.buildDirectory.dir("preserved-portable-data")
val preservePortableData by tasks.registering {
    doLast {
        check(!preservedPortableData.get().asFile.exists()) {
            "A previous portable-data backup still exists at ${preservedPortableData.get()}; restore it before packaging again."
        }
        val data = portableApplication.get().dir("data").asFile
        if (data.exists()) {
            copy { from(data); into(preservedPortableData) }
        }
    }
}

val restorePortableData by tasks.registering {
    onlyIf { tasks.getByName("createDistributable").state.failure == null }
    doLast {
        val application = portableApplication.get().asFile
        application.resolve("portable/tmp").mkdirs()
        application.resolve("portable/logs").mkdirs()
        if (preservedPortableData.get().asFile.exists()) {
            copy { from(preservedPortableData); into(application.resolve("data")) }
            delete(preservedPortableData)
        }
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    // Gradle can clear incremental task outputs before doFirst executes.
    dependsOn(preservePortableData)
    finalizedBy(restorePortableData)
}

tasks.register<JavaExec>("playbackSmoke") {
    group = "verification"
    description = "Exercise real MP3/FLAC decoding, pause, seek, volume and queue advancement."
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.PlaybackSmokeKt")
    args(rootProject.file("app/src/main/assets/reference.mp3").absolutePath,
        layout.buildDirectory.dir("reports/playback-smoke").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}

tasks.register<JavaExec>("uiSmoke") {
    group = "verification"
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.UiSmokeKt")
    systemProperty("synctone.data.dir", layout.buildDirectory.dir("ui-smoke/data").get().asFile.absolutePath)
    args(rootProject.file("app/src/main/assets/reference.mp3").absolutePath,
        layout.buildDirectory.dir("reports/ui-smoke").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}

tasks.register<JavaExec>("lyricsSmoke") {
    group = "verification"
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.LyricsSmokeKt")
    args(layout.buildDirectory.dir("reports/lyrics-smoke").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}

tasks.register<JavaExec>("lyricRasterSnapshotSmoke") {
    group = "verification"
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    val main = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = main.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.LyricRasterSnapshotSmokeKt")
    args(layout.buildDirectory.dir("reports/lyric-raster-snapshots").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}

tasks.register<JavaExec>("libraryUiSmoke") {
    group = "verification"
    description = "Render and scroll 180 tracks across library pages at desktop and minimum sizes."
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.LibraryUiSmokeKt")
    args(rootProject.file("app/src/main/assets/reference.mp3").absolutePath,
        layout.buildDirectory.dir("reports/library-ui-smoke").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}

tasks.register<JavaExec>("motionHandoffSmoke") {
    group = "verification"
    description = "Compare the last detail overlay frame with its source text at 100% and 150% scaling."
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.MotionHandoffSmokeKt")
    args(layout.buildDirectory.dir("reports/motion-handoff-smoke").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}

tasks.register<JavaExec>("controlsUiSmoke") {
    group = "verification"
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.ControlsUiSmokeKt")
    args(layout.buildDirectory.dir("reports/controls-ui-smoke").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}

tasks.register<JavaExec>("progressSnapshotSmoke") {
    group = "verification"
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.ProgressSnapshotSmokeKt")
    args(rootProject.file("app/src/main/assets/reference.mp3").absolutePath,
        layout.buildDirectory.dir("reports/progress-snapshot-smoke").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}

tasks.register<JavaExec>("sharedButtonsSmoke") {
    group = "verification"
    description = "Check the shared mobile/desktop buttons, disabled states, keys and cancelled presses."
    val main = kotlin.targets.getByName("desktop").compilations.getByName("main")
    val tests = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = main.output.allOutputs + tests.output.allOutputs + tests.runtimeDependencyFiles!!
    mainClass.set("dev.salt.desktop.SharedButtonsSmokeKt")
    args(layout.buildDirectory.dir("reports/shared-buttons-smoke").get().asFile.absolutePath)
    dependsOn("desktopTestClasses")
}
