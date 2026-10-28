package dev.salt.desktop.sync

import dev.salt.replica.sync.*
import org.json.JSONObject
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

internal class LanDiscovery(
    address: InetAddress, identity: SyncIdentity, name: String, port: Int,
    private val onPeer: (String, SyncPeer?) -> Unit,
) : AutoCloseable {
    private val type = "${SYNC_SERVICE}local."
    private val dns = JmDNS.create(address, "SyncTone-${identity.id.take(8)}")
    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) { dns.requestServiceInfo(type, event.name, true) }
        override fun serviceRemoved(event: ServiceEvent) { onPeer(event.name, null) }
        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            if (info.getPropertyString("v") != SYNC_PROTOCOL.toString()) return
            val host = info.inet4Addresses.firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: return
            val peer = runCatching { SyncPeer.parse(JSONObject().put("id", info.getPropertyString("id"))
                .put("name", info.getPropertyString("name")).put("pin", info.getPropertyString("pin"))
                .put("host", host).put("port", info.port)) }.getOrNull() ?: return
            if (peer.id != identity.id) onPeer(event.name, peer)
        }
    }
    init {
        try {
            dns.addServiceListener(type, listener)
            dns.registerService(ServiceInfo.create(type, "SyncTone-${identity.id.take(8)}-$port", port, 0, 0,
                mapOf("id" to identity.id, "name" to name, "pin" to identity.pin, "v" to SYNC_PROTOCOL.toString())))
        } catch (e: Exception) { dns.close(); throw e }
    }
    override fun close() { dns.removeServiceListener(type, listener); dns.unregisterAllServices(); dns.close() }
}
