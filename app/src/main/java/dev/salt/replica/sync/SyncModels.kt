package dev.salt.replica.sync

import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest

const val SYNC_PROTOCOL = 2
const val SYNC_SERVICE = "_synctone._tcp."

/** A phone can expose both its Wi-Fi uplink and its hotspot at the same time. */
fun syncLanIpv4Addresses(addresses: Iterable<InetAddress>): List<String> = addresses
    .filter { it is Inet4Address && it.isSiteLocalAddress }
    .mapNotNull { it.hostAddress }
    .distinct()

fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun fileDigest(input: InputStream): String {
    val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128 * 1024)
    while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer,0,n) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
fun safeMusicName(name: String, hash: String, flac: Boolean): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_").trim(' ','.').take(100).ifBlank { "音乐" }
    return "$base-${hash.take(10)}.${if (flac) "flac" else "mp3"}"
}
data class SyncSong(val hash: String, val title: String, val artist: String, val album: String, val filename: String, val size: Long, val flac: Boolean) {
    fun json() = JSONObject().put("hash",hash).put("title",title).put("artist",artist).put("album",album).put("filename",filename).put("size",size).put("flac",flac)
    companion object { fun parse(o: JSONObject) = SyncSong(o.getString("hash"),o.getString("title"),o.getString("artist"),o.getString("album"),o.getString("filename"),o.getLong("size"),o.getBoolean("flac")).also {
        require(it.hash.matches(Regex("[a-f0-9]{64}")) && it.size in 1..20_000_000_000L && listOf(it.title,it.artist,it.album,it.filename).all { text->text.length<=1024 })
    } }
}
data class SyncPeer(val id: String, val name: String, val host: String, val port: Int, val pin: String) {
    fun json() = JSONObject().put("id",id).put("name",name).put("host",host).put("port",port).put("pin",pin)
    companion object { fun parse(o: JSONObject) = SyncPeer(o.getString("id"),o.getString("name").take(40),o.getString("host"),o.getInt("port"),o.getString("pin")).also {
        require(it.id.length in 1..80 && it.port in 1..65535 && it.pin.matches(Regex("[a-f0-9]{64}")))
    } }
}
data class PairPrompt(val peerName: String, val code: String, val incoming: Boolean, val localConfirmed: Boolean = false)
enum class SyncPhase { READY, SCANNING, COMPARING, WAITING, TRANSFERRING, PAUSED, FAILED, COMPLETE }
data class LanSyncState(
    val localName: String = "", val address: String = "", val serving: Boolean = false,
    val peers: List<SyncPeer> = emptyList(), val peer: SyncPeer? = null, val prompt: PairPrompt? = null,
    val phase: SyncPhase = SyncPhase.READY, val songs: List<SyncSong> = emptyList(),
    val selected: Set<String> = emptySet(), val completed: Set<String> = emptySet(),
    val currentHash: String? = null, val bytes: Long = 0, val totalBytes: Long = 0,
    val historyChanges: Int = 0, val skipped: Int = 0, val message: String = "", val error: String? = null,
    val remoteSending: Boolean = false,
    val comparison: SyncComparison? = null
)
