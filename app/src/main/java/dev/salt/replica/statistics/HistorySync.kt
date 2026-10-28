package dev.salt.replica.statistics

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

fun songIdentity(title: String, artist: String): String {
    fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
        .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    val key = normalize(title) + "\u0000" + normalize(artist.ifBlank { "未知歌手" })
    return MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class HistoryRecord(
    val id: String, val origin: String, val session: String, val kind: String,
    val track: StatTrack, val start: Long, val end: Long, val ms: Long, val revision: Long
) {
    fun valid(): Boolean = id.length in 1..180 && origin.length in 1..80 && session.length in 1..80 &&
        kind in listOf("span", "play") && track.id == songIdentity(track.title, track.artist) &&
        track.title.length <= 1024 && track.artist.length <= 1024 && track.album.length <= 1024 &&
        start >= 0 && end >= start && ms >= 0 && (kind != "span" || (end > start && ms <= end - start + 2_000)) && revision > 0
    fun json() = JSONObject().put("id", id).put("origin", origin).put("session", session).put("kind", kind)
        .put("title", track.title).put("artist", track.artist).put("album", track.album)
        .put("start", start).put("end", end).put("ms", ms).put("revision", revision)
    companion object {
        fun parse(o: JSONObject): HistoryRecord {
            val title = o.getString("title"); val artist = o.getString("artist")
            return HistoryRecord(o.getString("id"), o.getString("origin"), o.getString("session"), o.getString("kind"),
                StatTrack(songIdentity(title, artist), title, artist, o.getString("album")),
                o.getLong("start"), o.getLong("end"), o.getLong("ms"), o.getLong("revision")).also { require(it.valid()) }
        }
    }
}

fun acceptsHistory(existing: HistoryRecord?, incoming: HistoryRecord, localOrigin: String): Boolean {
    if (!incoming.valid() || incoming.origin == localOrigin) return false
    if (existing == null) return true
    return existing.origin == incoming.origin && existing.session == incoming.session && existing.kind == incoming.kind &&
        existing.track.id == incoming.track.id && existing.start == incoming.start && incoming.revision > existing.revision &&
        incoming.end >= existing.end && incoming.ms >= existing.ms
}
