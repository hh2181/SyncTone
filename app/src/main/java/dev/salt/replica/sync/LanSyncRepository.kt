package dev.salt.replica.sync

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.os.StatFs
import androidx.core.content.ContextCompat
import dev.salt.replica.BuildConfig
import dev.salt.replica.statistics.HistoryRecord
import dev.salt.replica.statistics.StatisticsRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LanSyncRepository private constructor(context: Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("sync",Context.MODE_PRIVATE)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val coordination=Any()
    private val lifecycle=kotlinx.coroutines.sync.Mutex()
    private val identity by lazy { SyncIdentity(app) }
    private val history=StatisticsRepository.get(app)
    private val library=SyncLibrary(app)
    private val mutable=MutableStateFlow(LanSyncState(localName=prefs.getString("name",Build.MODEL).orEmpty()))
    val state:StateFlow<LanSyncState> = mutable
    private val services=ConcurrentHashMap<String,SyncPeer>()
    private var server:Server?=null
    private var discovery:LanDiscovery?=null
    private val visiblePages=mutableSetOf<Any>()
    @Volatile private var pageOpen=false
    private var startJob:Job?=null
    private var transfer:Job?=null
    @Volatile private var cancelling:Job?=null
    private var pairing:Job?=null
    @Volatile private var currentCall:Call?=null
    @Volatile private var leaseTime=0L
    @Volatile private var remoteTransfer:String?=null
    @Volatile private var remotePeer:String?=null
    private data class Pending(val id:String,val name:String,val pin:String,val nonce:String,val backToken:String,
                               val token:String=UUID.randomUUID().toString()+UUID.randomUUID(),val created:Long=SystemClock.elapsedRealtime(),@Volatile var approved:Boolean=false)
    @Volatile private var pending:Pending?=null
    private val clients=ConcurrentHashMap<String,okhttp3.OkHttpClient>()
    private val activeStreams=java.util.concurrent.Semaphore(2)
    private data class Manifest(val token:String,val songs:List<SyncSong>)
    private val manifests=ConcurrentHashMap<String,Manifest>()
    private var lastPair=0L
    init {
        restorePlan()
        scope.launch { LibraryRegistry.revision.drop(1).collect {
            if(server!=null) runCatching {
                val songs=library.refresh()
                change { state -> if(state.comparison?.localRoot != syncCatalogRoot(songs)) state.copy(comparison=null) else state }
            }.onFailure { failure(it) }
        } }
        scope.launch { while(isActive) {
            delay(5_000)
            if(pageOpen&&!busy()&&pairing?.isActive!=true&&pending==null) {
                val addresses=runCatching{localAddresses()}.getOrDefault(emptyList())
                val port=server?.listeningPort
                val next=if(port!=null)addresses.joinToString("\n"){"$it:$port"}else ""
                if(port==null&&addresses.isNotEmpty())start()
                else if(next!=state.value.address)change{it.copy(address=next,serving=addresses.isNotEmpty(),comparison=null)}
            }
        } }
    }
    private fun change(block:(LanSyncState)->LanSyncState){mutable.update(block)}
    private fun trace(message:String){if(BuildConfig.DEBUG)android.util.Log.d("SyncTone",message)}
    private fun trusted(peer:String)=prefs.getString("peer.$peer.pin",null)
    private fun inbound(peer:String)=prefs.getString("peer.$peer.in",null)
    private fun outbound(peer:String)=prefs.getString("peer.$peer.out",null)
    private fun storeTrust(id:String,pin:String,inToken:String,outToken:String){check(prefs.edit().putString("peer.$id.pin",pin).putString("peer.$id.in",inToken).putString("peer.$id.out",outToken).commit())}
    fun rename(name:String){val next=name.trim().take(32).ifBlank{Build.MODEL};prefs.edit().putString("name",next).apply();change{it.copy(localName=next)};scope.launch{discovery?.stop();discovery=null;startDiscovery()}}
    @Synchronized fun setPageOpen(owner:Any,open:Boolean){
        if(open)visiblePages+=owner else visiblePages-=owner
        pageOpen=visiblePages.isNotEmpty()
        if(pageOpen)start() else stopIfIdle()
    }
    fun start(){if(startJob?.isActive==true)return;startJob=scope.launch{
        runCatching{
            lifecycle.lock()
            try {
            require(localAddresses().isNotEmpty()){"尚未连接 Wi-Fi 或局域网"}
            change{if(it.phase==SyncPhase.FAILED&&it.error=="尚未连接 Wi-Fi 或局域网")it.copy(phase=SyncPhase.READY,error=null)else it}
            change{if(it.phase in listOf(SyncPhase.READY,SyncPhase.SCANNING))it.copy(error=null,message="正在读取音乐库")else it}
            library.refresh()
            if(server==null){val candidate=Server();candidate.makeSecure(identity.serverFactory,null);candidate.start(15_000,false);server=candidate}
            change{it.copy(serving=true,address=localAddresses().joinToString("\n"){address->"$address:${server!!.listeningPort}"})}
            startDiscovery();stopIfIdle()
            } finally {lifecycle.unlock()}
        }.onFailure{failure(it)}
    }}
    private fun startDiscovery(){val host=server?:return;if(!pageOpen&&!busy())return
        if(discovery==null){
            change{if(it.phase in listOf(SyncPhase.READY,SyncPhase.SCANNING))it.copy(message="正在发布设备广播")else it}
            discovery=LanDiscovery(app,identity,scope,{name,peer->
            if(peer==null)services.remove(name)else {
                services.entries.removeAll{it.value.id==peer.id&&it.key!=name}
                services[name]=peer
            }
            change{it.copy(peers=services.values.distinctBy{p->p.id}.sortedBy{p->p.name},
                peer=if(peer!=null&&it.peer?.id==peer.id&&it.peer.pin==peer.pin)peer else it.peer)}
        },{error->change{it.copy(error=error)}}, {
            change{if(it.phase in listOf(SyncPhase.READY,SyncPhase.SCANNING))it.copy(message="已发布设备广播")else it}
        })
        }
        discovery?.start(state.value.localName,host.listeningPort)
    }
    fun scan(){if(busy())return;change{it.copy(phase=SyncPhase.SCANNING,error=null)};scope.launch{
        discovery?.stop();discovery=null;services.clear();change{it.copy(peers=emptyList())};start();startJob?.join();startDiscovery();delay(8_000)
        change{if(it.phase==SyncPhase.SCANNING)it.copy(phase=SyncPhase.READY,message=if(it.peers.isEmpty())"暂未发现设备" else "扫描完成")else it}
    }}
    private fun busy()=transfer?.isActive==true||cancelling?.isActive==true||remoteTransfer!=null||state.value.phase==SyncPhase.TRANSFERRING
    private fun stopIfIdle(){scope.launch{delay(700);lifecycle.lock();try{if(!pageOpen&&!busy()&&pairing?.isActive!=true&&pending==null){discovery?.stop();discovery=null;server?.stop();server=null;services.clear();manifests.clear();change{it.copy(serving=false,address="",peers=emptyList())};stopService()}}finally{lifecycle.unlock()}}}
    private fun localAddresses():List<String> {
        val cm=app.getSystemService(ConnectivityManager::class.java)
        val networks=cm.allNetworks.filter{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true||cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true}
        val networkAddresses=networks.flatMap{cm.getLinkProperties(it)?.linkAddresses.orEmpty()}.map{it.address}
        // Some Android releases omit tethering interfaces from ConnectivityManager.
        // Merge them even when an upstream Wi-Fi address has already been found.
        val interfaceAddresses=runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter{it.isUp&&!it.isLoopback&&(it.name.startsWith("wlan")||it.name.startsWith("ap")||it.name.startsWith("eth"))}
                .flatMap{it.inetAddresses.toList()}
        }.getOrDefault(emptyList())
        return syncLanIpv4Addresses(networkAddresses+interfaceAddresses)
    }
    private fun client(peer:SyncPeer)=clients.getOrPut(peer.pin){identity.client(peer.pin)}
    private fun url(peer:SyncPeer,path:String,params:Map<String,String> = emptyMap()):HttpUrl {
        require(InetAddress.getByName(peer.host).isSiteLocalAddress){"仅支持局域网地址"}
        return HttpUrl.Builder().scheme("https").host(peer.host).port(peer.port).addPathSegments(path.removePrefix("/")).apply{params.forEach{(k,v)->addQueryParameter(k,v)}}.build()
    }
    private suspend fun request(peer:SyncPeer,path:String,body:JSONObject?=null,params:Map<String,String> = emptyMap(),auth:Boolean=true):JSONObject {
        val builder=Request.Builder().url(url(peer,path,params)).header("X-Sync-Device",identity.id)
        if(auth)builder.header("Authorization","Bearer ${outbound(peer.id)?:error("请重新配对设备")}")
        if(body!=null)builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return suspendCancellableCoroutine { continuation ->
            val call=client(peer).newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException) {
                    if(continuation.isActive)continuation.resumeWithException(e)
                }
                override fun onResponse(call:Call,response:okhttp3.Response) {
                    val result=runCatching { response.use { r ->
                        val raw=requireNotNull(r.body).byteStream().readBoundedSyncJson()
                        val json=JSONObject(raw)
                        check(r.isSuccessful){json.optString("error","连接失败 (${r.code})")};json
                    } }
                    if(continuation.isActive)result.fold(continuation::resume,continuation::resumeWithException)
                }
            })
        }
    }
    fun connectAddress(address:String)=synchronized(coordination){if(busy()||pending!=null||pairing?.isActive==true)return@synchronized;change{it.copy(phase=SyncPhase.COMPARING,comparison=null,error=null)};pairing=scope.launch{runCatching{
        start();startJob?.join();check(server!=null){"本机连接服务尚未就绪"}
        val target=parseSyncAddress(address);val host=target.host;val port=target.port
        require(InetAddress.getByName(host).isSiteLocalAddress){"请输入局域网地址"}
        val url=HttpUrl.Builder().scheme("https").host(host).port(port).addPathSegment("identity").build()
        val observedPin=java.util.concurrent.atomic.AtomicReference<String>()
        val bootstrap=identity.client(null,observedPin::set)
        val discovered=try{bootstrap.newCall(Request.Builder().url(url).build()).execute().use{r->
            check(r.isSuccessful);parseSyncIdentity(JSONObject(requireNotNull(r.body).byteStream().readBoundedSyncJson()),observedPin.get(),identity.id,target)
        }}finally{dispose(bootstrap)}
        services["manual:${discovered.id}"]=discovered;change{it.copy(peers=services.values.distinctBy{p->p.id})};synchronized(coordination){pairing=null;select(discovered)}
    }.onFailure{if(it !is CancellationException)failure(it)};if(pairing===currentCoroutineContext()[Job])pairing=null;stopIfIdle()}}
    fun select(peer:SyncPeer)=synchronized(coordination){if(busy()||pending!=null||pairing?.isActive==true)return@synchronized;pairing?.cancel();change{it.copy(peer=peer,songs=emptyList(),selected=emptySet(),completed=emptySet(),error=null,phase=SyncPhase.COMPARING,comparison=null)}
        pairing=scope.launch{runCatching{
            val old=trusted(peer.id)
            check(old==null||old==peer.pin){"设备身份已变化，请移除配对后重新连接"}
            if(old==peer.pin&&outbound(peer.id)!=null){compare(peer);return@runCatching}
            val nonce=UUID.randomUUID().toString();val back=UUID.randomUUID().toString()+UUID.randomUUID()
            val result=requestSyncPair{request(peer,"pair",JSONObject().put("id",identity.id).put("name",state.value.localName).put("pin",identity.pin).put("nonce",nonce).put("backToken",back),auth=false)}
            val code=pairCode(peer.pin,nonce);check(result.getString("code")==code)
            change{it.copy(prompt=PairPrompt(peer.name,code,false),phase=SyncPhase.WAITING)}
            trace("Pair request accepted")
            withTimeout(120_000){while(true){
                delay(700);ensureActive()
                if(state.value.prompt?.localConfirmed==true){
                    trace("Checking remote pairing approval")
                    val status=request(peer,"pair/status",JSONObject().put("nonce",nonce),auth=false)
                    trace("Remote pairing approved=${status.optBoolean("approved")}")
                    if(status.optBoolean("approved")){
                        val token=status.getString("token")
                        require(token.length==72){"配对令牌无效"}
                        request(peer,"pair/finish",JSONObject().put("nonce",nonce).put("token",token),auth=false)
                        storeTrust(peer.id,peer.pin,back,token);change{it.copy(prompt=null)};break
                    }
                }
            }}
            compare(peer)
        }.onFailure{if(it is TimeoutCancellationException)failure(IllegalStateException("配对已超时，请重新选择设备"))else if(it !is CancellationException)failure(it)};stopIfIdle()}
    }
    private fun pairCode(otherPin:String,nonce:String):String {
        val pins=listOf(identity.pin,otherPin).sorted().joinToString(":")
        return (digest("$pins:$nonce".toByteArray()).take(12).toLong(16)%1_000_000).toString().padStart(6,'0')
    }
    fun confirmPair(){val p=state.value.prompt?:return
        trace("Pair confirmed incoming=${p.incoming}, pending=${pending!=null}")
        if(p.incoming){synchronized(coordination){pending?.approved=true};change{it.copy(prompt=p.copy(localConfirmed=true),message="等待对方确认")}}
        else change{it.copy(prompt=p.copy(localConfirmed=true))}
    }
    fun cancelPair(){synchronized(coordination){pairing?.cancel();pending=null;change{it.copy(prompt=null,phase=SyncPhase.READY,message="配对已取消")}};stopIfIdle()}
    fun forgetPeer(){if(busy()||state.value.prompt!=null)return;val peer=state.value.peer?:return
        pairing?.cancel();scope.launch{runCatching{
            val oldPin=trusted(peer.id)
            check(prefs.edit().remove("peer.${peer.id}.pin").remove("peer.${peer.id}.in").remove("peer.${peer.id}.out").remove("plan").commit())
            listOfNotNull(oldPin,peer.pin).distinct().forEach{clients.remove(it)?.let(::dispose)}
            change{it.copy(peer=null,songs=emptyList(),selected=emptySet(),completed=emptySet(),comparison=null,prompt=null,phase=SyncPhase.READY,error=null,message="已移除配对")}
        }.onFailure{failure(it)}}
    }
    fun refreshComparison(){state.value.peer?.let(::select)}
    private suspend fun compare(peer:SyncPeer){
        change{it.copy(phase=SyncPhase.COMPARING,prompt=null,message="正在对比音乐库")}
        val difference=compareAndReport(peer);val missing=difference.receive
        change{it.copy(peer=peer,phase=SyncPhase.READY,songs=missing,selected=missing.map{song->song.hash}.toSet(),completed=emptySet(),skipped=difference.summary.commonCount,message="已连接 ${peer.name}",error=null,historyChanges=0,bytes=0,totalBytes=0,comparison=difference.summary)}
    }
    private suspend fun compareAndReport(peer:SyncPeer):SyncLibraryDifference {
        val remote=readSyncManifest{offset,snapshot->request(peer,"manifest",params=mapOf("offset" to offset.toString(),"snapshot" to snapshot))}
        val difference=compareSyncLibraries(library.refresh(),remote)
        request(peer,"comparison",JSONObject().put("comparison",difference.summary.json()).put("device",JSONObject()
            .put("id",identity.id).put("name",state.value.localName).put("pin",identity.pin).put("port",requireNotNull(server).listeningPort)))
        return difference
    }
    fun toggle(hash:String){if(busy())return;change{it.copy(selected=if(hash in it.selected)it.selected-hash else it.selected+hash,phase=SyncPhase.READY)}}
    fun selectAll(visible:Set<String>?=null){if(busy())return;change{it.copy(selected=toggleSyncSelection(it.selected,it.songs,visible),phase=SyncPhase.READY)}}
    fun begin()=synchronized(coordination){if(busy()||pairing?.isActive==true||pending!=null)return@synchronized;val s=state.value;val peer=s.peer?:return@synchronized
        if(s.songs.isEmpty()&&(s.comparison?.missingLocal?:0)>0){refreshComparison();return@synchronized}
        if(trusted(peer.id)!=peer.pin||outbound(peer.id)==null){select(peer);return@synchronized}
        val songs=s.songs.filter{it.hash in s.selected};persistPlan(peer,songs,s.completed)
        try{startService()}catch(e:Exception){failure(e);return@synchronized}
        change{it.copy(phase=SyncPhase.TRANSFERRING,error=null,message="正在建立传输",totalBytes=songs.sumOf{song->song.size},completed=it.completed.intersect(it.selected),historyChanges=0)}
        val previousTransfer=transfer
        transfer=scope.launch{
            val task=UUID.randomUUID().toString()
            try{
                previousTransfer?.join()
                start();startJob?.join()
                check(server!=null){"本机连接服务尚未就绪"}
                val currentIdentity=parseSyncIdentity(request(peer,"identity",auth=false),peer.pin,identity.id,SyncAddress(peer.host,peer.port))
                require(currentIdentity.id==peer.id){"设备身份已变化，请重新配对"}
                request(peer,"transfer/begin",JSONObject().put("task",task))
                val own=library.refresh().map{it.hash}.toMutableSet()
                for(song in songs){
                    ensureActive()
                    if(song.hash in own){change{it.copy(completed=it.completed+song.hash)};continue}
                    val part=library.part(song)
                    if(part.length()>song.size)part.delete()
                    val offset=part.length()
                    val needed=(song.size-offset)+song.size+8_000_000
                    check(StatFs(app.filesDir.path).availableBytes>=needed){"存储空间不足，需要至少 ${needed/1_048_576} MB"}
                    change{it.copy(currentHash=song.hash,message="正在接收 ${song.title}",bytes=songs.filter{track->track.hash in it.completed}.sumOf{track->track.size}+offset)}
                    receiveFile(peer,song,task,part,songs.filter{it.hash in state.value.completed}.sumOf{it.size})
                    check(part.length()==song.size){"连接中断，可继续接收"}
                    if(part.inputStream().use(::fileDigest)!=song.hash){part.delete();error("文件校验失败，请重试")}
                    library.publish(song,part);own+=song.hash
                    change{it.copy(completed=it.completed+song.hash,bytes=songs.filter{track->track.hash in (it.completed+song.hash)}.sumOf{track->track.size})}
                    persistPlan(peer,songs,state.value.completed)
                }
                change{it.copy(currentHash=null,message="正在双向合并播放记录")}
                val changes=exchangeHistory(peer)
                val comparison=compareAndReport(peer).summary
                prefs.edit().remove("plan").apply();library.refresh()
                change{it.copy(phase=SyncPhase.COMPLETE,message="同步完成",historyChanges=changes,bytes=it.totalBytes,comparison=comparison)}
            }catch(e:CancellationException){throw e}catch(e:Exception){if(currentCoroutineContext().isActive)failure(e)}finally{
                currentCall=null
                withContext(NonCancellable){withTimeoutOrNull(3_000){runCatching{request(peer,"transfer/end",JSONObject().put("task",task))}}}
                if(transfer===currentCoroutineContext()[Job]){transfer=null;stopService();stopIfIdle()}
            }
        }
    }
    private suspend fun receiveFile(peer:SyncPeer,song:SyncSong,task:String,part:java.io.File,completedBytes:Long){
        var failures=0
        while(part.length()<song.size){
            currentCoroutineContext().ensureActive()
            val offset=part.length()
            change{it.copy(message="正在接收 ${song.title}",bytes=completedBytes+offset)}
            val req=Request.Builder().url(url(peer,"file",mapOf("hash" to song.hash,"offset" to offset.toString(),"task" to task)))
                .header("X-Sync-Device",identity.id).header("Authorization","Bearer ${outbound(peer.id)}").build()
            val call=client(peer).newCall(req);currentCall=call
            try{
                call.execute().use{response->
                    check(response.isSuccessful){"文件传输中断 (${response.code})"}
                    check(response.header("X-Sync-Hash")==song.hash&&response.header("X-Sync-Offset")==offset.toString()){"文件响应不匹配"}
                    check(response.body?.contentLength()==song.size-offset){"文件响应长度不匹配"}
                    val input=requireNotNull(response.body).byteStream()
                    java.io.FileOutputStream(part,true).use{out->
                        val buffer=ByteArray(131072);var position=offset;var last=0L
                        while(true){
                            currentCoroutineContext().ensureActive()
                            val n=input.read(buffer);if(n<0)break
                            position+=n;check(position<=song.size){"文件长度不匹配"};out.write(buffer,0,n)
                            val now=SystemClock.elapsedRealtime()
                            if(now-last>90){last=now;change{it.copy(bytes=completedBytes+position)}}
                        }
                        out.fd.sync()
                    }
                }
                if(part.length()<song.size)throw java.io.EOFException("连接中断，可继续接收")
            }catch(e:IOException){
                currentCoroutineContext().ensureActive()
                if(++failures>3)throw e
                trace("Retrying file transfer from ${part.length()}, attempt=$failures")
                change{it.copy(message="连接中断，正在重连 ($failures/3)")}
                delay(1_000L*failures)
            }finally{currentCall=null}
        }
    }
    fun pause(){currentCall?.cancel();transfer?.cancel();change{it.copy(phase=SyncPhase.PAUSED,currentHash=null,message="同步已暂停，可继续接收",error=null)};stopService()}
    fun cancelTransfer()=synchronized(coordination){if(cancelling?.isActive==true)return@synchronized;val old=transfer;pause();cancelling=scope.launch{try{old?.join();check(prefs.edit().remove("plan").commit());change{it.copy(phase=SyncPhase.READY,message="同步已取消，已完成歌曲已保留",selected=it.selected-it.completed)}}finally{cancelling=null;stopIfIdle()}} }
    private fun persistPlan(peer:SyncPeer,songs:List<SyncSong>,done:Set<String>){prefs.edit().putString("plan",JSONObject().put("peer",peer.json()).put("songs",JSONArray(songs.map{it.json()})).put("done",JSONArray(done.toList())).toString()).commit()}
    private fun restorePlan(){runCatching{val raw=prefs.getString("plan",null)?:return;val json=JSONObject(raw);val rows=json.getJSONArray("songs");val songs=(0 until rows.length()).map{SyncSong.parse(rows.getJSONObject(it))};val done=json.getJSONArray("done")
        change{it.copy(peer=SyncPeer.parse(json.getJSONObject("peer")),songs=songs,selected=songs.map{song->song.hash}.toSet(),completed=(0 until done.length()).map{done.getString(it)}.toSet(),phase=SyncPhase.PAUSED,message="上次同步尚未完成，可继续")}
    }}
    private suspend fun exchangeHistory(peer:SyncPeer):Int {
        var changed=0
        val info=request(peer,"history/info")
        val remoteEpoch=info.getString("epoch");val epochKey="cursor.${peer.id}.epoch";val valueKey="cursor.${peer.id}.value"
        var after=if(prefs.getString(epochKey,null)==remoteEpoch)prefs.getLong(valueKey,0)else 0
        require(remoteEpoch.length in 1..80)
        while(true){currentCoroutineContext().ensureActive();val batch=parseSyncHistoryBatch(request(peer,"history",params=mapOf("after" to after.toString())),remoteEpoch,after)
            changed+=history.merge(batch.records);after=batch.next
            prefs.edit().putString(epochKey,remoteEpoch).putLong(valueKey,after).commit();if(batch.records.size<SYNC_PAGE_SIZE)break
        }
        val localEpoch=history.epoch()
        var pushAfter=if(info.optString("receivedEpoch")==localEpoch)info.optLong("receivedCursor")else 0
        require(pushAfter>=0)
        while(true){currentCoroutineContext().ensureActive();val page=history.export(pushAfter)
            val result=request(peer,"history/import",JSONObject().put("epoch",page.epoch).put("next",page.next).put("records",JSONArray(page.records.map{it.json()})))
            changed+=result.optInt("changed");pushAfter=page.next;if(page.records.size<200)break
        }
        return changed
    }
    private fun failure(e:Throwable){android.util.Log.e("SyncTone","LAN synchronization failed",e);change{it.copy(phase=SyncPhase.FAILED,prompt=null,currentHash=null,error=e.message?.take(160)?:"连接失败，请重试",message="同步未完成")}}
    private fun startService(){ContextCompat.startForegroundService(app,Intent(app,SyncService::class.java))}
    private fun stopService(){if(remoteTransfer==null)app.stopService(Intent(app,SyncService::class.java))}
    fun checkLease(){if(remoteTransfer!=null&&SystemClock.elapsedRealtime()-leaseTime>120_000){remoteTransfer=null;remotePeer=null;change{it.copy(remoteSending=false,message="对方连接已结束")};stopService();stopIfIdle()}}
    fun serviceTimedOut(){pause();remoteTransfer=null;remotePeer=null;change{it.copy(remoteSending=false,error="后台同步已达到系统时间限制，可返回继续")};stopIfIdle()}

    private inner class Server:NanoHTTPD(0){
        override fun serve(session:IHTTPSession):Response { return try{
            trace("LAN request ${session.uri}")
            require(InetAddress.getByName(session.remoteIpAddress).isSiteLocalAddress){"仅接受局域网连接"}
            val peerId=session.headers["x-sync-device"].orEmpty()
            val public=session.uri in listOf("/identity","/pair","/pair/status","/pair/finish")
            if(!public){val expected=inbound(peerId)?:return json(401,JSONObject().put("error","请先配对"));val token=session.headers["authorization"].orEmpty().removePrefix("Bearer ")
                if(!MessageDigest.isEqual(expected.toByteArray(),token.toByteArray()))return json(401,JSONObject().put("error","设备验证失败"))
                leaseTime=SystemClock.elapsedRealtime()
            }
            val post=session.uri in listOf("/pair","/pair/status","/pair/finish","/comparison","/transfer/begin","/transfer/end","/history/import")
            require(session.method==if(post)Method.POST else Method.GET){"不支持的请求方法"}
            val body=if(post){val length=session.headers["content-length"]?.toLongOrNull()?:0;require(length in 1..SYNC_MAX_JSON){"请求过大"};val files=hashMapOf<String,String>();session.parseBody(files);JSONObject(files["postData"].orEmpty())}else JSONObject()
            when(session.uri){
                "/identity"->json(200,JSONObject().put("v",SYNC_PROTOCOL).put("id",identity.id).put("name",state.value.localName).put("pin",identity.pin))
                "/pair"->synchronized(coordination){
                    check(pageOpen&&!busy()&&pairing?.isActive!=true){"对方暂不可配对"};check(SystemClock.elapsedRealtime()-lastPair>1_000){"请稍后再试"};lastPair=SystemClock.elapsedRealtime()
                    val p=Pending(body.getString("id"),body.getString("name").take(40),body.getString("pin"),body.getString("nonce"),body.getString("backToken"))
                    require(p.id.length in 1..80&&p.id!=identity.id&&p.nonce.length==36&&p.pin.matches(Regex("[a-f0-9]{64}"))&&p.backToken.length==72)
                    check(pending==null||SystemClock.elapsedRealtime()-pending!!.created>120_000){"对方正在处理另一项配对"}
                    pending=p;change{it.copy(prompt=PairPrompt(p.name,pairCode(p.pin,p.nonce),true),error=null)}
                    scope.launch{delay(120_000);synchronized(coordination){if(pending===p){pending=null;change{it.copy(prompt=null)};stopIfIdle()}}}
                    json(200,JSONObject().put("code",pairCode(p.pin,p.nonce)))
                }
                "/pair/status"->synchronized(coordination){val p=requireNotNull(pending);require(body.getString("nonce")==p.nonce&&SystemClock.elapsedRealtime()-p.created<120_000);json(200,JSONObject().put("approved",p.approved).apply{if(p.approved)put("token",p.token)})}
                "/pair/finish"->synchronized(coordination){val p=requireNotNull(pending);require(p.approved&&SystemClock.elapsedRealtime()-p.created<120_000&&body.getString("nonce")==p.nonce&&body.getString("token")==p.token)
                    storeTrust(p.id,p.pin,p.token,p.backToken);pending=null;change{it.copy(prompt=null,message="已配对 ${p.name}")};stopIfIdle();json(200,JSONObject().put("ok",true))}
                "/manifest"->{val offset=session.parameters["offset"]?.firstOrNull()?.toIntOrNull()?:0;require(offset>=0)
                    val manifest=if(offset==0)Manifest(UUID.randomUUID().toString(),runBlocking{library.refresh()}).also{manifests[peerId]=it}
                        else requireNotNull(manifests[peerId]){"请重新对比音乐库"}.also{require(it.token==session.parameters["snapshot"]?.firstOrNull()){"音乐库快照已失效，请重新连接"}}
                    val all=manifest.songs
                    json(200,JSONObject().put("v",SYNC_PROTOCOL).put("snapshot",manifest.token).put("total",all.size).put("songs",JSONArray(all.drop(offset).take(200).map{it.json()})))}
                "/comparison"->{
                    val summary=SyncComparison.parse(body.getJSONObject("comparison")).reversed()
                    val local=runBlocking{library.refresh()}
                    check(summary.localCount==local.size&&summary.localRoot==syncCatalogRoot(local)){"曲库已变化，请重新对比"}
                    val device=SyncPeer.parse(body.getJSONObject("device").put("host",session.remoteIpAddress))
                    require(device.id==peerId&&device.pin==trusted(peerId)){"设备身份不匹配"}
                    synchronized(coordination){
                        check(remoteTransfer==null||remotePeer==peerId){"正在与其他设备同步"}
                        change{it.copy(peer=device,comparison=summary,
                            songs=if(it.peer?.id==peerId&&it.comparison?.remoteRoot==summary.remoteRoot)it.songs else emptyList(),
                            selected=if(it.peer?.id==peerId&&it.comparison?.remoteRoot==summary.remoteRoot)it.selected else emptySet())}
                    }
                    json(200,JSONObject().put("ok",true))
                }
                "/transfer/begin"->synchronized(coordination){check(remoteTransfer==null||remotePeer==peerId){"对方正在同步"};check(transfer?.isActive!=true&&state.value.phase!=SyncPhase.TRANSFERRING&&pairing?.isActive!=true&&pending==null){"对方正在处理其他任务，请稍后重试"}
                    val task=body.getString("task");require(task.length in 1..80);startService();remoteTransfer=task;remotePeer=peerId;leaseTime=SystemClock.elapsedRealtime();change{it.copy(remoteSending=true,message="正在向已配对设备发送音乐")};json(200,JSONObject().put("ok",true))}
                "/transfer/end"->synchronized(coordination){if(remotePeer==peerId&&remoteTransfer==body.optString("task")){remoteTransfer=null;remotePeer=null;change{it.copy(remoteSending=false,message="对方同步已结束")};stopService();stopIfIdle()};json(200,JSONObject().put("ok",true))}
                "/file"->{
                    require(remotePeer==peerId&&remoteTransfer!=null&&remoteTransfer==session.parameters["task"]?.firstOrNull())
                    val hash=session.parameters["hash"]?.firstOrNull().orEmpty();val offset=session.parameters["offset"]?.firstOrNull()?.toLongOrNull()?:0
                    val song=library.songs.firstOrNull{it.hash==hash}?:return json(404,JSONObject().put("error","歌曲已不可用"))
                    require(offset in 0 until song.size);check(activeStreams.tryAcquire()){"传输忙，请稍后重试"}
                    val input=try{library.open(hash,offset)}catch(e:Exception){activeStreams.release();throw e}
                    val stream=object:FilterInputStream(input){
                        private var closed=false
                        override fun read(b:ByteArray,off:Int,len:Int):Int{leaseTime=SystemClock.elapsedRealtime();return super.read(b,off,len)}
                        override fun close(){if(!closed){closed=true;try{super.close()}finally{activeStreams.release()}}}
                    }
                    newFixedLengthResponse(Response.Status.OK,"application/octet-stream",stream,song.size-offset).apply{addHeader("X-Sync-Hash",hash);addHeader("X-Sync-Offset",offset.toString())}
                }
                "/history/info"->runBlocking{json(200,JSONObject().put("epoch",history.epoch()).put("receivedEpoch",prefs.getString("incoming.$peerId.epoch","")).put("receivedCursor",prefs.getLong("incoming.$peerId.cursor",0)))}
                "/history"->runBlocking{val after=session.parameters["after"]?.firstOrNull()?.toLongOrNull()?:0;require(after>=0);val page=history.export(after);json(200,JSONObject().put("epoch",page.epoch).put("next",page.next).put("records",JSONArray(page.records.map{it.json()})))}
                "/history/import"->runBlocking{val list=body.getJSONArray("records");require(list.length()<=200);val rows=(0 until list.length()).map{HistoryRecord.parse(list.getJSONObject(it))}
                    val epoch=body.getString("epoch");val next=body.getLong("next");require(epoch.length in 1..80&&next>=0);val changed=history.merge(rows)
                    prefs.edit().putString("incoming.$peerId.epoch",epoch).putLong("incoming.$peerId.cursor",next).commit();json(200,JSONObject().put("changed",changed))}
                else->json(404,JSONObject().put("error","不支持的请求"))
            }
        }catch(e:Exception){json(400,JSONObject().put("error",e.message?.take(160)?:"请求失败"))} }
        private fun json(code:Int,body:JSONObject)=newFixedLengthResponse(Response.Status.lookup(code),"application/json; charset=utf-8",body.toString())
    }
    companion object {
        private fun dispose(client:okhttp3.OkHttpClient){client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
        @Volatile private var instance:LanSyncRepository?=null
        fun get(context:Context)=instance?:synchronized(this){instance?:LanSyncRepository(context).also{instance=it}}
    }
}
