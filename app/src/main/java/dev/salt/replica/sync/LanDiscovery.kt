package dev.salt.replica.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class LanDiscovery(context: Context,private val identity: SyncIdentity,private val scope: CoroutineScope,
                   private val onPeer:(String,SyncPeer?)->Unit,private val onError:(String)->Unit,
                   private val onBroadcastReady:()->Unit) {
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val wifi=context.applicationContext.getSystemService(WifiManager::class.java)
    private val resolver=Mutex()
    private var lock:WifiManager.MulticastLock?=null
    private var discovery:NsdManager.DiscoveryListener?=null
    private var registration:NsdManager.RegistrationListener?=null
    @Suppress("DEPRECATION") fun start(name:String,port:Int){
        if(discovery!=null)return
        lock=wifi.createMulticastLock("synctone-discovery").apply{setReferenceCounted(false);acquire()}
        val register=object:NsdManager.RegistrationListener{
            override fun onServiceRegistered(info:NsdServiceInfo){if(registration===this)onBroadcastReady()}
            override fun onRegistrationFailed(info:NsdServiceInfo,code:Int){if(registration===this)onError("本机设备广播失败 ($code)，可使用地址连接")}
            override fun onServiceUnregistered(info:NsdServiceInfo){}
            override fun onUnregistrationFailed(info:NsdServiceInfo,code:Int){}
        }
        registration=register
        runCatching { nsd.registerService(NsdServiceInfo().apply{
            serviceName="SyncTone-${identity.id.take(8)}-$port";serviceType=SYNC_SERVICE;setPort(port)
            setAttribute("id",identity.id);setAttribute("name",name);setAttribute("pin",identity.pin);setAttribute("v",SYNC_PROTOCOL.toString())
        },NsdManager.PROTOCOL_DNS_SD,register) }.onFailure{onError("无法广播设备，请检查局域网权限")}
        val listener=object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(type:String){}
            override fun onDiscoveryStopped(type:String){}
            override fun onStartDiscoveryFailed(type:String,code:Int){onError("扫描失败 ($code)，可使用地址连接")}
            override fun onStopDiscoveryFailed(type:String,code:Int){}
            override fun onServiceLost(info:NsdServiceInfo){onPeer(info.serviceName,null)}
            override fun onServiceFound(info:NsdServiceInfo){scope.launch{
                resolver.withLock { withTimeoutOrNull(8_000){suspendCancellableCoroutine<Unit>{continuation->
                    nsd.resolveService(info,object:NsdManager.ResolveListener{
                        override fun onResolveFailed(service:NsdServiceInfo,code:Int){if(continuation.isActive)continuation.resume(Unit)}
                        override fun onServiceResolved(service:NsdServiceInfo){
                            fun attr(key:String)=service.attributes[key]?.toString(Charsets.UTF_8).orEmpty()
                            val peer=runCatching{SyncPeer.parse(org.json.JSONObject().put("id",attr("id")).put("name",attr("name")).put("pin",attr("pin")).put("host",service.host.hostAddress).put("port",service.port))}.getOrNull()
                            if(continuation.isActive){if(peer!=null&&peer.id!=identity.id&&attr("v")==SYNC_PROTOCOL.toString())onPeer(service.serviceName,peer);continuation.resume(Unit)}
                        }
                    })
                }} }
            }}
        }
        discovery=listener
        runCatching{nsd.discoverServices(SYNC_SERVICE,NsdManager.PROTOCOL_DNS_SD,listener)}.onFailure{onError("扫描失败，请检查局域网权限")}
    }
    fun stop(){discovery?.let{runCatching{nsd.stopServiceDiscovery(it)}};discovery=null;registration?.let{runCatching{nsd.unregisterService(it)}};registration=null;lock?.let{if(it.isHeld)it.release()};lock=null}
}
