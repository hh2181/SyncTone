package dev.salt.desktop.statistics

import dev.salt.desktop.library.DesktopTrack
import dev.salt.replica.statistics.ListeningRecorder
import dev.salt.replica.statistics.StatTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

class DesktopStatistics {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "synctone-statistics") }.asCoroutineDispatcher()
    private val mutable = MutableStateFlow(StatisticsState())
    val state = mutable.asStateFlow()
    private var database: StatisticsRepository? = null
    val history: StatisticsRepository get() = requireNotNull(database) { "统计尚未就绪" }
    var persistent = false
        private set
    private val recorder = ListeningRecorder { database?.record(it) }
    private var playing = false
    private var checkpoint = 0L
    private var observedElapsed: Long? = null
    private var observedUtc = 0L

    suspend fun initialize(directory: Path?) = withContext(dispatcher) {
        database = try {
            StatisticsRepository(directory).also { persistent = directory != null }
        } catch (e: Exception) {
            Logger.getLogger("dev.salt.desktop.statistics").log(Level.WARNING, "Statistics opened in memory", e)
            StatisticsRepository(null)
        }
        publish()
    }

    suspend fun metadata(tracks: List<DesktopTrack>) = work {
        history.metadata(tracks.map { StatTrack(it.id, it.title, it.artist, it.album) })
    }

    suspend fun transition(id: String?, utc: Long, elapsed: Long) = work {
        observe(false, utc, elapsed)
        recorder.transition(id, false, utc, elapsed)
        playing = false
        checkpoint = elapsed
        observedElapsed = elapsed
        observedUtc = utc
    }

    suspend fun sample(active: Boolean, utc: Long, elapsed: Long) = work { observe(active, utc, elapsed) }

    private fun observe(active: Boolean, utc: Long, elapsed: Long) {
        // Sleep or a suspended process is not evidence of uninterrupted audio output.
        observedElapsed?.let { previous ->
            if (elapsed - previous > 5_000 && playing) {
                recorder.playing(false, observedUtc, previous)
                recorder.playing(false, utc, elapsed)
                playing = false
            }
        }
        if (active != playing) {
            recorder.playing(active, utc, elapsed)
            playing = active
            checkpoint = elapsed
        } else if (elapsed - checkpoint >= 1_000) {
            recorder.checkpoint(utc, elapsed)
            checkpoint = elapsed
        }
        observedElapsed = elapsed
        observedUtc = utc
    }

    suspend fun refresh() = work { history.refresh() }

    private suspend fun work(block: () -> Unit) = withContext(dispatcher) {
        if (database == null) return@withContext
        try { block(); publish() }
        catch (e: Exception) {
            Logger.getLogger("dev.salt.desktop.statistics").log(Level.WARNING, "Statistics update failed", e)
            mutable.value = mutable.value.copy(loading = false, error = true)
        }
    }

    private fun publish() {
        mutable.value = history.state.value.copy(error = history.state.value.error || !persistent)
    }

    suspend fun close(utc: Long, elapsed: Long) {
        try {
            work { observe(false, utc, elapsed); history.close() }
        } finally { dispatcher.close() }
    }
}
