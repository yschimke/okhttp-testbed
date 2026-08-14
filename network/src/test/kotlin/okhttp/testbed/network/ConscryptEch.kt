/*
 * Copyright (c) 2026 OkHttp Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp.testbed.network

import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okio.ByteString
import org.conscrypt.Conscrypt
import org.conscrypt.DomainEncryptionMode
import org.conscrypt.NetworkSecurityPolicy
import org.conscrypt.metrics.CertificateTransparencyVerificationReason

/**
 * Everything needed to encrypt a client hello on the JVM, which as of this writing is not
 * something OkHttp can arrange on its own.
 *
 * OkHttp resolves the ECH config list out of the DNS HTTPS record and hands it to its platform;
 * on Android that platform passes it to Conscrypt and ECH happens. On the JVM `ConscryptPlatform`
 * takes the same parameter and drops it — `configureTlsExtensions` sets session tickets and ALPN
 * and returns. So the config list reaches [okhttp3.Route] and stops there, which is exactly what
 * [EchTest] reports.
 *
 * These types close that gap from outside OkHttp, using published API on both sides: OkHttp's
 * [Dns.Record.ServiceMetadata.echConfigList] for the config, and Conscrypt's
 * `Conscrypt.setEchConfigList` for the socket. What that measures is *Conscrypt's* half of ECH,
 * not OkHttp's — see [EchConscryptTest] for what does and doesn't follow from a pass here.
 */
object ConscryptEch {
  /**
   * True if the Conscrypt on the classpath can encrypt a client hello.
   *
   * `setEchConfigList` exists only on the `google3-export` branch. A released Conscrypt has the
   * rest of this class's API and not this method, so the check is for the method rather than for
   * the provider — anything else would report "Conscrypt is missing" for a Conscrypt that is
   * there and merely too old.
   */
  val isSupported: Boolean by lazy {
    try {
      Conscrypt::class.java.getMethod("setEchConfigList", SSLSocket::class.java, ByteArray::class.java)
      Conscrypt.isAvailable()
    } catch (_: NoSuchMethodException) {
      false
    } catch (_: NoClassDefFoundError) {
      false
    }
  }

  val version: String
    get() = Conscrypt.version().let { "${it.major()}.${it.minor()}.${it.patch()}" }

  /** An [SSLContext] from Conscrypt, trusting whatever the JVM trusts. */
  fun sslContext(trustManager: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
      init(null, arrayOf(trustManager), null)
    }

  /**
   * Conscrypt's trust manager, trusting whatever the JVM trusts.
   *
   * Conscrypt's, rather than the JDK's, because Conscrypt names the authentication type `GENERIC`
   * for TLS 1.3 and the JDK's validator has a fixed list that the name isn't on. Every case in
   * [EchConscryptTest] failed on that, before a client hello was sent:
   *
   *     javax.net.ssl.SSLHandshakeException: Unknown authType: GENERIC
   *     Caused by: java.security.cert.CertificateException: Unknown authType: GENERIC
   *         at sun.security.validator.EndEntityChecker.checkTLSServer(EndEntityChecker.java:290)
   *
   * Conscrypt reaches the JDK's trust manager through `Platform.checkServerTrusted`, which calls
   * the two-argument method, so wrapping it in an [javax.net.ssl.X509ExtendedTrustManager] to
   * reach the socket-aware overload does not avoid this — that was tried, and it also stopped
   * Conscrypt finding [EchEnablingTrustManager.getNetworkSecurityPolicy], which turned ECH off.
   * Using the trust manager that belongs to the same stack is the fix that leaves both alone.
   *
   * The trust anchors are the same either way: this reads the JVM's default trust store.
   */
  fun platformTrustManager(): X509TrustManager = Conscrypt.getDefaultX509TrustManager()
}

/**
 * A trust manager that also answers Conscrypt's question about whether ECH is allowed here.
 *
 * Conscrypt asks the platform, through a [NetworkSecurityPolicy] it looks for by calling a
 * `getNetworkSecurityPolicy` method on the configured trust manager. On Android the platform
 * supplies one and the answer comes from `network_security_config.xml`. On the JVM the default
 * answers `UNKNOWN`, which Conscrypt reads as "no" — so a config list set on the socket is
 * ignored and the handshake goes out in the clear. That is the second half of why ECH does not
 * work on the JVM today, and it is not something a caller can fix by configuring OkHttp.
 *
 * The method is found reflectively, which is why it has to be public on a public class.
 */
class EchEnablingTrustManager(
  private val delegate: X509TrustManager,
) : X509TrustManager by delegate {
  @Suppress("unused") // Called by Conscrypt, reflectively.
  fun getNetworkSecurityPolicy(): NetworkSecurityPolicy = Policy

  private object Policy : NetworkSecurityPolicy {
    override fun isCertificateTransparencyVerificationRequired(hostname: String): Boolean = false

    override fun getCertificateTransparencyVerificationReason(
      hostname: String,
    ): CertificateTransparencyVerificationReason = CertificateTransparencyVerificationReason.UNKNOWN

    // ENABLED rather than REQUIRED: a hostname with no config list should still connect, in the
    // clear, the way a browser does. REQUIRED would turn tls12.tls-ech.dev into a failure to
    // connect rather than the negative result it is there to give.
    override fun getDomainEncryptionMode(hostname: String): DomainEncryptionMode = DomainEncryptionMode.ENABLED
  }
}

/**
 * A [Dns] that remembers the ECH config list it saw for each hostname.
 *
 * OkHttp reads the config list out of the HTTPS record and carries it on the [okhttp3.Route], but
 * a route is only visible once a connection exists — too late to configure the socket that makes
 * it. So this watches the same records on their way past, and [EchSocketFactory] reads them back
 * when a socket for that hostname is created.
 *
 * Only [Dns.newCall] carries service metadata; [Dns.lookup] is addresses only. Both are delegated,
 * because OkHttp uses each in different circumstances.
 */
class EchRecordingDns(
  private val delegate: Dns,
) : Dns {
  private val echConfigLists = ConcurrentHashMap<String, ByteString>()

  operator fun get(hostname: String): ByteString? = echConfigLists[hostname.lowercase()]

  override fun lookup(hostname: String) = delegate.lookup(hostname)

  override fun newCall(request: Dns.Request): Dns.Call = RecordingCall(delegate.newCall(request))

  private inner class RecordingCall(
    private val delegate: Dns.Call,
  ) : Dns.Call by delegate {
    override fun enqueue(callback: Dns.Callback) {
      delegate.enqueue(
        object : Dns.Callback {
          override fun onRecords(
            call: Dns.Call,
            last: Boolean,
            records: List<Dns.Record>,
          ) {
            for (record in records) {
              if (record !is Dns.Record.ServiceMetadata) continue
              val echConfigList = record.echConfigList ?: continue
              echConfigLists[record.hostname.lowercase()] = echConfigList
            }
            callback.onRecords(call, last, records)
          }

          override fun onFailure(
            call: Dns.Call,
            e: IOException,
          ) {
            callback.onFailure(call, e)
          }
        },
      )
    }
  }
}

/**
 * Applies the config list [dns] saw for a hostname to the socket about to connect to it.
 *
 * This is the call OkHttp's `ConscryptPlatform` doesn't make. Doing it here means the handshake
 * is encrypted, and means the retry path isn't covered: when a server rejects a stale config it
 * offers a fresh one, and reading that back needs `SSL_get0_ech_retry_configs`, which Conscrypt
 * exposes on Android as `EchConfigMismatchException` and does not expose at all on the JVM.
 */
class EchSocketFactory(
  private val delegate: SSLSocketFactory,
  private val dns: EchRecordingDns,
) : DelegatingSSLSocketFactory(delegate) {
  /** The hostnames a config list was applied to, so a test can assert it was applied at all. */
  val encryptedHostnames: MutableList<String> = mutableListOf()

  override fun createSocket(
    socket: Socket,
    host: String,
    port: Int,
    autoClose: Boolean,
  ): SSLSocket {
    val sslSocket = delegate.createSocket(socket, host, port, autoClose) as SSLSocket
    val echConfigList = dns[host]
    if (echConfigList != null) {
      Conscrypt.setEchConfigList(sslSocket, echConfigList.toByteArray())
      synchronized(encryptedHostnames) { encryptedHostnames += host }
    }
    return sslSocket
  }
}
