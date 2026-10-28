package dev.salt.replica.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.salt.replica.lyrics.Lyrics
import dev.salt.replica.lyrics.LyricsParser
import dev.salt.replica.statistics.StatTrack
import dev.salt.replica.statistics.StatisticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Track(
    val id: String,
    val uri: Uri,
    val localFile: File?,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val artwork: Bitmap?,
    val lyrics: Lyrics
)

@Stable
class PlayerStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    var tracks by mutableStateOf<List<Track>>(emptyList()); private set
    var index by mutableIntStateOf(0); private set
    var queueIds by mutableStateOf(loadQueueIds()); private set
    val activeQueue: List<Track>
        get() = if (queueIds.isEmpty()) tracks
        else queueIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }
    val track get() = activeQueue.getOrNull(index)
    var playlists by mutableStateOf(loadPlaylists()); private set
    val favorites: List<Track> get() = tracks.filter { it.id in favoriteIds }
    var playing by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var shuffle by mutableStateOf(false); private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_ALL); private set
    var coarsePosition by mutableLongStateOf(0L); private set
    val framePosition = mutableLongStateOf(0L)
    var favoriteIds by mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet())
        private set
    val statistics = StatisticsRepository.get(context)
    private var controller: MediaController? = null
    private var anchorMs = 0L
    private var anchorTime = 0L
    private var pendingIndex = prefs.getInt("index", 0)
    private var pendingPosition = prefs.getLong("position", 0)
    private val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()

    init {
        future.addListener({
            runCatching {
                controller = future.get().apply {
                    addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            playing = player.isPlaying
                            index = player.currentMediaItemIndex.coerceAtLeast(0)
                            shuffle = player.shuffleModeEnabled
                            repeat = player.repeatMode
                            synchronize()
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playing = isPlaying
                        }
                        override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = e.localizedMessage }
                    })
                }
                ready = true
                populate()
            }.onFailure { error = it.localizedMessage }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val sample = File(context.filesDir, "reference.mp3")
        if (!sample.exists()) context.assets.open("reference.mp3").use { input -> sample.outputStream().use(input::copyTo) }
        val sources = mutableListOf(Uri.fromFile(sample) to "reference")
        val imported = runCatching { JSONArray(prefs.getString("files", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until imported.length()) {
            val raw = imported.optString(i)
            val uri = parseStoredUri(raw) ?: continue
            val id = stableId(uri)
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (uri.scheme == "file" && !File(uri.path.orEmpty()).exists()) continue
            sources += uri to id
        }
        val result = sources.mapNotNull { (uri, id) -> runCatching { readTrack(uri, id) }.getOrNull() }
        statistics.metadata(result.map { StatTrack(it.id, it.title, it.artist, it.album) })
        withContext(Dispatchers.Main) { tracks = result; populate() }
    }

    suspend fun importAudio(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val readable = uris.mapNotNull { uri ->
            runCatching {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                readTrack(uri, stableId(uri))
            }.getOrNull()
        }
        val registered = dev.salt.replica.sync.LibraryRegistry.add(context, readable.map { it.uri })
        val accepted = registered.added.toSet()
        val additions = readable.filter { it.uri in accepted }
        withContext(Dispatchers.Main) {
            val previous = tracks.size
            tracks = (tracks + additions).distinctBy { it.id }
            statistics.metadata(tracks.map { StatTrack(it.id, it.title, it.artist, it.album) })
            if (additions.isNotEmpty()) {
                pendingIndex = previous.coerceAtMost(tracks.lastIndex)
                pendingPosition = 0
                populate(force = true)
                controller?.play()
            } else if (readable.isEmpty()) error = "Unable to open audio"
        }
    }

    suspend fun importLyrics(uri: Uri) = withContext(Dispatchers.IO) {
        val current = track ?: return@withContext
        val text = context.contentResolver.openInputStream(uri)?.use { LyricsParser.decode(it.readBytes()) } ?: return@withContext
        val lyrics = LyricsParser.parse(text, current.duration, "LRC")
        if (lyrics.lines.isNotEmpty()) {
            lyricFile(current.id).apply { parentFile?.mkdirs(); writeText(text) }
            withContext(Dispatchers.Main) { tracks = tracks.map { if (it.id == current.id) it.copy(lyrics = lyrics) else it } }
        }
    }

    private fun readTrack(uri: Uri, id: String): Track {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") retriever.setDataSource(uri.path.orEmpty())
            else retriever.setDataSource(context, uri)
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "未命名歌曲"
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: displayName.substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().ifBlank { "未知专辑" }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val art = retriever.embeddedPicture?.let { bytes ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 1080).coerceAtLeast(1)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
            val sidecar = lyricFile(id)
            val localFile = uri.takeIf { it.scheme == "file" }?.path?.let(::File)
            val lyrics = runCatching { when {
                sidecar.exists() -> LyricsParser.parse(sidecar.readText(), duration, "LRC")
                localFile != null -> LyricsParser.fromMp3(localFile, duration)
                else -> context.contentResolver.openInputStream(uri)?.use { LyricsParser.fromMp3(it, duration) }
                    ?: Lyrics(emptyList(), "NONE")
            } }.onFailure { Log.w("SyncTone", "Unable to read embedded lyrics for $id", it) }
                .getOrDefault(Lyrics(emptyList(), "NONE"))
            return Track(id, uri, localFile, title, artist, album, duration, art, lyrics)
        } finally { retriever.release() }
    }

    private fun populate(force: Boolean = false) {
        val player = controller ?: return
        if (tracks.isEmpty() || (!force && player.mediaItemCount != 0)) return
        val restored = queueIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }
        if (restored.isEmpty()) queueIds = emptyList()
        val effective = restored.ifEmpty { tracks }
        player.setMediaItems(effective.map(::mediaItem), pendingIndex.coerceIn(0, effective.lastIndex), pendingPosition)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
        synchronize()
    }

    private fun mediaItem(t: Track) = MediaItem.Builder().setMediaId(t.id).setUri(t.uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()).build()

    /** Replaces the playback queue with [list] and starts at [startIndex]. */
    fun playQueue(list: List<Track>, startIndex: Int = 0, play: Boolean = true) {
        if (list.isEmpty()) return
        val player = controller ?: return
        queueIds = list.map { it.id }
        player.setMediaItems(list.map(::mediaItem), startIndex.coerceIn(0, list.lastIndex), 0)
        player.prepare()
        if (play) player.play()
        synchronize()
        save()
    }

    /** Plays [id] if it is already in the active queue, otherwise switches the queue to the library. */
    fun playTrack(id: String) {
        val player = controller ?: return
        val position = activeQueue.indexOfFirst { it.id == id }
        if (position >= 0) {
            player.seekToDefaultPosition(position)
            player.play()
            synchronize()
        } else {
            val libraryIndex = tracks.indexOfFirst { it.id == id }
            if (libraryIndex >= 0) playQueue(tracks, libraryIndex)
        }
    }

    fun playlistTracks(playlist: Playlist): List<Track> =
        playlist.trackIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }

    fun createPlaylist(name: String): Playlist {
        val clean = name.trim().ifBlank { "未命名歌单" }
        val playlist = Playlist(java.util.UUID.randomUUID().toString(), clean, emptyList(), System.currentTimeMillis())
        playlists = playlists + playlist
        persistPlaylists()
        return playlist
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().ifBlank { return }
        playlists = playlists.map { if (it.id == id) it.copy(name = clean) else it }
        persistPlaylists()
    }

    fun deletePlaylist(id: String) {
        playlists = playlists.filterNot { it.id == id }
        persistPlaylists()
    }

    fun addToPlaylist(id: String, trackId: String) {
        playlists = playlists.map {
            if (it.id == id && trackId !in it.trackIds) it.copy(trackIds = it.trackIds + trackId) else it
        }
        persistPlaylists()
    }

    fun removeFromPlaylist(id: String, trackId: String) {
        playlists = playlists.map {
            if (it.id == id) it.copy(trackIds = it.trackIds - trackId) else it
        }
        persistPlaylists()
    }

    fun synchronize() {
        val player = controller ?: return
        anchorMs = player.currentPosition
        anchorTime = SystemClock.elapsedRealtime()
        framePosition.longValue = anchorMs
        coarsePosition = anchorMs
    }
    fun onFrame() {
        val elapsed = SystemClock.elapsedRealtime() - anchorTime
        framePosition.longValue = (anchorMs + if (playing) elapsed else 0).coerceIn(0, track?.duration ?: Long.MAX_VALUE)
        if (elapsed >= 200) synchronize()
    }
    fun toggle() { controller?.run { if (isPlaying) pause() else play() } }
    fun playWhenReady(value: Boolean) { controller?.run { if (value) play() else pause() } }
    fun seek(ms: Long) { controller?.seekTo(ms.coerceIn(0, track?.duration ?: 0)); synchronize() }
    fun select(i: Int) { controller?.seekToDefaultPosition(i); controller?.play(); synchronize() }
    fun previous() { controller?.seekToPreviousMediaItem(); synchronize() }
    fun next() { controller?.seekToNextMediaItem(); synchronize() }
    fun cycleMode() {
        controller?.run {
            when {
                shuffleModeEnabled -> { shuffleModeEnabled = false; repeatMode = Player.REPEAT_MODE_ALL }
                repeatMode == Player.REPEAT_MODE_ALL -> repeatMode = Player.REPEAT_MODE_ONE
                else -> { repeatMode = Player.REPEAT_MODE_ALL; shuffleModeEnabled = true }
            }
        }
    }
    fun save() {
        prefs.edit().putInt("index", index).putLong("position", framePosition.longValue)
            .putString("queue", JSONArray(queueIds).toString()).apply()
    }
    fun release() { save(); MediaController.releaseFuture(future) }

    fun isFavorite(id: String): Boolean = id in favoriteIds

    fun toggleFavorite(id: String) {
        favoriteIds = if (id in favoriteIds) favoriteIds - id else favoriteIds + id
        prefs.edit().putStringSet("favorites", favoriteIds).apply()
    }

    private fun loadQueueIds(): List<String> {
        val json = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrDefault(JSONArray())
        return buildList { for (i in 0 until json.length()) json.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    }

    private fun loadPlaylists(): List<Playlist> {
        val json = runCatching { JSONArray(prefs.getString("playlists", "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) continue
                val ids = item.optJSONArray("ids") ?: JSONArray()
                add(Playlist(id, name, buildList {
                    for (j in 0 until ids.length()) ids.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                }, item.optLong("createdAt")))
            }
        }
    }

    private fun persistPlaylists() {
        val json = JSONArray()
        playlists.forEach { p ->
            json.put(JSONObject().put("id", p.id).put("name", p.name).put("createdAt", p.createdAt)
                .put("ids", JSONArray(p.trackIds)))
        }
        prefs.edit().putString("playlists", json.toString()).apply()
    }

    private fun stableId(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private fun parseStoredUri(raw: String): Uri? = when {
        raw.isBlank() -> null
        raw.contains("://") -> Uri.parse(raw)
        else -> Uri.fromFile(File(raw))
    }

    private fun lyricFile(id: String): File = File(context.filesDir, "lyrics/$id.lrc")
}

data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String>,
    val createdAt: Long = 0L
)
