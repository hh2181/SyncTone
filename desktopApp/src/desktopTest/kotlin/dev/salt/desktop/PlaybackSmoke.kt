package dev.salt.desktop

import dev.salt.desktop.library.DesktopTrackReader
import dev.salt.desktop.playback.*
import dev.salt.replica.equalizer.EqConfig
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.abs

fun main(args: Array<String>) = runBlocking {
    val reference = Path.of(args[0])
    val output = Path.of(args[1])
    Files.createDirectories(output)
    val unicodeFile = output.resolve("音乐 空格 テスト.mp3")
    Files.copy(reference, unicodeFile, StandardCopyOption.REPLACE_EXISTING)
    val flac = output.resolve("测试 FLAC.flac")
    val conversion = ProcessBuilder("ffmpeg.exe", "-hide_banner", "-loglevel", "error", "-y", "-i",
        reference.toString(), "-t", "3", "-vn", "-c:a", "flac", flac.toString()).inheritIO().start()
    check(conversion.waitFor() == 0) { "FFmpeg fixture generation failed." }
    val track = DesktopTrackReader.read(unicodeFile)
    check(track.title.isNotBlank())
    check(track.durationMs > 10_000)
    check(track.artwork?.isNotEmpty() == true)
    check(track.lyrics.lines.size > 30)
    val records = mutableListOf<JsonObject>()
    withContext(Dispatchers.IO) {
        VlcAudioEngine().use { engine ->
            val eq = EqConfig(true, listOf(-8, 0, 0, -68, -65, -26, 0, 0, 0, 0), false)
            engine.setEqualizer(eq)
            suspend fun await(description: String, predicate: (AudioSnapshot) -> Boolean): AudioSnapshot = withTimeout(12_000) {
                while (true) {
                    val snapshot = engine.snapshot()
                    check(snapshot.status != AudioStatus.Failed) { "Decoder failed: $description" }
                    if (predicate(snapshot)) return@withTimeout snapshot
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") error(description)
            }
            engine.setVolume(0)
            engine.open(unicodeFile)
            val playing = await("MP3 playback") { it.status == AudioStatus.Playing && it.positionMs > 600 }
            val applied = engine.equalizerRuntime()
            check(applied.connected && applied.model?.config == eq) { "EQ did not process actual decoded PCM: $applied" }
            check(abs(applied.model!!.responseDb(500.0) + 6.52467457) < .001)
            engine.setPaused(true)
            val paused = await("pause") { it.status == AudioStatus.Paused }
            delay(400)
            check(abs(engine.snapshot().positionMs - paused.positionMs) <= 100) { "Clock advanced while paused." }
            engine.seek(30_000)
            await("paused seek") { abs(it.positionMs - 30_000) <= 700 }
            engine.setVolume(37)
            val volume = await("volume update") { abs(it.volume - 37) <= 1 }.volume
            engine.setVolume(0)
            engine.setPaused(false)
            val resumed = await("resume after seek") { it.status == AudioStatus.Playing && it.positionMs > 30_300 }
            records += buildJsonObject {
                put("format", "MP3"); put("positionMs", playing.positionMs); put("durationMs", playing.durationMs)
                put("pausedClockStable", true); put("seekResumeMs", resumed.positionMs); put("volumeVerified", volume)
                put("customEqProcessedPcm", true); put("eqProcessingRate", applied.model!!.sampleRate)
                put("unicodePath", true); put("artworkBytes", track.artwork!!.size); put("lyricLines", track.lyrics.lines.size)
            }
            engine.open(flac)
            val decoded = await("FLAC playback") { it.status == AudioStatus.Playing && it.positionMs > 400 }
            await("natural completion") { it.status == AudioStatus.Ended }
            records += buildJsonObject { put("format", "FLAC"); put("durationMs", decoded.durationMs); put("naturalEnd", true) }
            engine.stop()
            engine.close()
        }
    }
    val report = buildJsonObject { put("passed", true); put("checks", JsonArray(records)) }
    Files.writeString(output.resolve("verification.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
    println(report)
}
