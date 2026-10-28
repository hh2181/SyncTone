package dev.salt.desktop.sync

import dev.salt.replica.sync.digest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.Proxy
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal class SyncIdentity(val id: String, preferences: SyncPreferences) {
    private val certificate = preferences.get("certificate")?.let(HeldCertificate::decode)
        ?: HeldCertificate.Builder().commonName("SyncTone-$id").duration(3650, TimeUnit.DAYS).build().also {
            preferences.edit("certificate" to it.certificatePem() + it.privateKeyPkcs8Pem())
        }
    val pin = digest(certificate.certificate.encoded)
    val serverFactory = HandshakeCertificates.Builder().heldCertificate(certificate).build().sslContext().serverSocketFactory

    fun client(pin: String?, observe: ((String) -> Unit)? = null): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates unsupported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("缺少设备证书")
                val actual = digest(chain[0].encoded)
                if (pin != null && actual != pin) throw CertificateException("设备身份已变化，请重新配对")
                observe?.invoke(actual)
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            // Pairing binds the certificate to the device; LAN IPs can change.
            .hostnameVerifier { _, _ -> true }.proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
