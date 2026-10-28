package dev.salt.replica.sync

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.salt.replica.statistics.StatTrack
import dev.salt.replica.statistics.StatisticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

object LibraryRegistry {
    val revision = MutableStateFlow(0L)
    private val registrations = Mutex()
    data class Addition(val added: List<Uri>, val duplicates: Int, val unreadable: Int)

    suspend fun add(context: Context, uris: List<Uri>, onChecking: (Int, Uri) -> Unit = { _, _ -> }): Addition =
        withContext(Dispatchers.IO) { registrations.withLock {
            val prefs=context.getSharedPreferences("player",Context.MODE_PRIVATE)
            val old=JSONArray(prefs.getString("files","[]")); val values=linkedSetOf<String>()
            for(i in 0 until old.length()) values+=old.getString(i)
            val known=hashSetOf<String>()
            val sample=File(context.filesDir,"reference.mp3")
            val existing=buildList {
                if(sample.exists())add(Uri.fromFile(sample))
                addAll(values.map { if(it.contains("://"))Uri.parse(it)else Uri.fromFile(File(it)) })
            }
            suspend fun fingerprint(uri: Uri): String? {
                currentCoroutineContext().ensureActive()
                return try {
                    SyncFileIndex.fingerprint(context,uri)
                } catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(_: Exception){null}
            }
            for(uri in existing)fingerprint(uri)?.let(known::add)
            val accepted=mutableListOf<Uri>();var duplicates=0;var unreadable=0
            for((index,uri) in uris.withIndex()) {
                onChecking(index,uri)
                if(uri.toString() in values){duplicates++;continue}
                val hash=fingerprint(uri)
                if(hash==null)unreadable++
                else if(!known.add(hash))duplicates++
                else {accepted+=uri;values+=uri.toString()}
            }
            if(accepted.isNotEmpty()){
                check(prefs.edit().putString("files",JSONArray(values.toList()).toString()).commit())
                revision.value++
            }
            runCatching{SyncFileIndex.persist(context)}
            Addition(accepted,duplicates,unreadable)
        } }
}
class SyncLibrary(private val app: Context) {
    private val mutex=Mutex()
    private val sources=ConcurrentHashMap<String,Uri>()
    @Volatile var songs: List<SyncSong> = emptyList(); private set
    suspend fun refresh(): List<SyncSong> = withContext(Dispatchers.IO) { mutex.withLock {
        val sample=File(app.filesDir,"reference.mp3")
        if(!sample.exists()) app.assets.open("reference.mp3").use { input->sample.outputStream().use(input::copyTo) }
        val uris=mutableListOf(Uri.fromFile(sample))
        val json=JSONArray(app.getSharedPreferences("player",Context.MODE_PRIVATE).getString("files","[]"))
        for(i in 0 until json.length()) uris+=SyncFileIndex.registeredUri(json.getString(i))
        val result=mutableListOf<SyncSong>(); val metadata=mutableListOf<StatTrack>()
        for(uri in uris.distinct()){
            currentCoroutineContext().ensureActive()
            try {
                val song=SyncFileIndex.song(app,uri)?:continue
                sources[song.hash]=uri;result+=song
                val localId=if(uri==Uri.fromFile(sample)) "reference" else digest(uri.toString().toByteArray()).take(24)
                metadata+=StatTrack(localId,song.title,song.artist,song.album)
            }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){}
        }
        runCatching{SyncFileIndex.persist(app,uris.map{it.toString()}.toSet())}
        songs=result.distinctBy { it.hash }; val valid=songs.map { it.hash }.toSet();sources.keys.retainAll(valid)
        StatisticsRepository.get(app).metadata(metadata)
        songs
    } }
    private fun open(uri: Uri): InputStream = if(uri.scheme=="file") FileInputStream(requireNotNull(uri.path))
        else requireNotNull(app.contentResolver.openInputStream(uri)) { "歌曲文件无法读取" }
    fun open(hash: String, offset: Long): InputStream {
        val song=songs.firstOrNull { it.hash==hash }?:error("歌曲已不可用")
        require(offset in 0..song.size)
        val uri=requireNotNull(sources[hash])
        check(open(uri).use(::fileDigest)==hash){"音乐文件已变化，请重新对比"}
        val input=open(uri);var remaining=offset
        try {while(remaining>0){val n=input.skip(remaining);if(n>0)remaining-=n else {check(input.read()>=0);remaining--}};return input}
        catch(e:Exception){input.close();throw e}
    }
    fun part(song: SyncSong)=File(app.filesDir,"sync-incoming/${song.hash}.part").apply { parentFile?.mkdirs() }
    suspend fun publish(song: SyncSong, file: File): Uri = withContext(Dispatchers.IO) {
        check(file.length()==song.size && file.inputStream().use(::fileDigest)==song.hash) { "文件校验失败，请重试" }
        val name=safeMusicName(song.filename,song.hash,song.flac)
        val uri=if(Build.VERSION.SDK_INT>=29){
            val values=ContentValues().apply {put(MediaStore.Audio.Media.DISPLAY_NAME,name);put(MediaStore.Audio.Media.MIME_TYPE,if(song.flac)"audio/flac" else "audio/mpeg");put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/sync");put(MediaStore.Audio.Media.IS_PENDING,1)}
            val dest=requireNotNull(app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values)){"无法创建音乐文件"}
            try{
                requireNotNull(app.contentResolver.openOutputStream(dest,"w")).use { output->file.inputStream().use {it.copyTo(output)} }
                app.contentResolver.update(dest,ContentValues().apply{put(MediaStore.Audio.Media.IS_PENDING,0)},null,null)
                dest
            }catch(e:Exception){app.contentResolver.delete(dest,null,null);throw e}
        }else{
            val folder=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),"sync").apply{mkdirs()}
            var dest=File(folder,name);var i=1
            while(dest.exists())dest=File(folder,"${name.substringBeforeLast('.')}-${i++}.${name.substringAfterLast('.')}")
            file.copyTo(dest);MediaScannerConnection.scanFile(app,arrayOf(dest.path),null,null);Uri.fromFile(dest)
        }
        try {
            val addition=LibraryRegistry.add(app,listOf(uri))
            if(addition.added.isEmpty()){
                removePublished(uri)
                refresh();val existing=sources[song.hash]?:error("歌曲未能登记到音乐库")
                file.delete();existing
            }else{file.delete();uri}
        }catch(e:Exception){
            val registered=JSONArray(app.getSharedPreferences("player",Context.MODE_PRIVATE).getString("files","[]"))
            if((0 until registered.length()).none{registered.getString(it)==uri.toString()})removePublished(uri)
            throw e
        }
    }
    private fun removePublished(uri:Uri){if(uri.scheme=="file")File(requireNotNull(uri.path)).delete()else app.contentResolver.delete(uri,null,null)}
}
