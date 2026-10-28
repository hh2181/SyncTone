package dev.salt.replica.sync

import android.content.Context
import dev.salt.replica.statistics.StatisticsRepository
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class SyncIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("sync",Context.MODE_PRIVATE)
    val id = StatisticsRepository.get(context).origin
    private val certificate = prefs.getString("certificate",null)?.let { HeldCertificate.decode(it) }
        ?: HeldCertificate.Builder().commonName("SyncTone-$id").duration(3650,TimeUnit.DAYS).build().also {
            check(prefs.edit().putString("certificate",it.certificatePem()+it.privateKeyPkcs8Pem()).commit())
        }
    val pin = digest(certificate.certificate.encoded)
    val serverFactory = HandshakeCertificates.Builder().heldCertificate(certificate).build().sslContext().serverSocketFactory
    fun client(pin: String?, onCertificate: ((String) -> Unit)? = null): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>,authType: String) { throw java.security.cert.CertificateException("Client certificate unsupported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>,authType: String) {
                if (chain.isEmpty() || (pin != null && digest(chain[0].encoded) != pin)) throw java.security.cert.CertificateException("设备身份已变化，请重新配对")
                onCertificate?.invoke(digest(chain[0].encoded))
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust)
            .proxy(java.net.Proxy.NO_PROXY)
            // The peer certificate is pinned above; peers are addressed by changing LAN IPs.
            .hostnameVerifier { _,_->true }.connectTimeout(8,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
            .writeTimeout(20,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
