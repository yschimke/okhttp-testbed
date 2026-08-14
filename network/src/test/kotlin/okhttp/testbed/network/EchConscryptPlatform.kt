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
// USES-OKHTTP-INTERNALS: is a Platform, which OkHttp only declares internally.
package okhttp.testbed.network

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.platform.Platform
import okio.ByteString
import org.conscrypt.Conscrypt

/**
 * OkHttp's `ConscryptPlatform`, plus the one call it doesn't make.
 *
 * [EchConscryptTest] shows Conscrypt can encrypt a client hello, but it shows it from a socket
 * factory — outside OkHttp, on a socket OkHttp then uses. That leaves the interesting question
 * open, because the config list it passes is one the suite kept for itself rather than the one
 * OkHttp resolved. This closes it: the config list arrives the way OkHttp delivers it, as the
 * `echConfigList` argument to [configureTlsExtensions], and the only thing added is the
 * [Conscrypt.setEchConfigList] call that `ConscryptPlatform` omits.
 *
 * So [EchPlatformTest] and [EchTest] make the same requests through the same public API, and
 * differ only by which platform is installed. What that difference measures is one call.
 *
 * Why this exists here rather than upstream: OkHttp's `master` can't compile against a Conscrypt
 * that has these methods, because no published Conscrypt does. [lysine-dev/okhttp#9559][pr] is
 * the change on the OkHttp side, and it builds against a Conscrypt built from source. This
 * reaches the same place from the other direction, with a platform written here.
 *
 * It is not a proposal for how OkHttp should do it. `ConscryptPlatform` is `final` and its
 * constructor is private, so this can't extend it and reimplements the parts it needs instead;
 * the upstream change is four lines in the class itself, and uses `setEchParameters`, a newer
 * Conscrypt API than the `setEchConfigList` the `google3-export` build here exposes.
 *
 * [pr]: https://github.com/lysine-dev/okhttp/pull/9559
 */
@OptIn(OkHttpInternalApi::class)
class EchConscryptPlatform : Platform() {
  private val provider = Conscrypt.newProvider()

  override fun newSSLContext(): SSLContext = SSLContext.getInstance("TLS", provider)

  /**
   * Conscrypt's own, wrapped so Conscrypt can find the policy that permits ECH.
   *
   * Both halves are load-bearing and both are covered by [ConscryptEch]: the JDK's trust manager
   * rejects the authType Conscrypt uses for TLS 1.3, and without the policy Conscrypt treats ECH
   * as not allowed and sends the hello in the clear whatever is set on the socket.
   */
  override fun platformTrustManager(): X509TrustManager = EchEnablingTrustManager(ConscryptEch.platformTrustManager())

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? = null

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
    echConfigList: ByteString?,
  ) {
    if (!Conscrypt.isConscrypt(sslSocket)) {
      super.configureTlsExtensions(sslSocket, hostname, protocols, echConfigList)
      return
    }

    Conscrypt.setUseSessionTickets(sslSocket, true)
    Conscrypt.setApplicationProtocols(sslSocket, alpnProtocolNames(protocols).toTypedArray())

    // The line this whole file exists for. `ConscryptPlatform` takes this argument and returns.
    if (echConfigList != null) {
      Conscrypt.setEchConfigList(sslSocket, echConfigList.toByteArray())
    }
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
    when {
      Conscrypt.isConscrypt(sslSocket) -> Conscrypt.getApplicationProtocol(sslSocket)
      else -> super.getSelectedProtocol(sslSocket)
    }

  override fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory =
    newSSLContext()
      .apply { init(null, arrayOf(trustManager), null) }
      .socketFactory

  override fun toString(): String = "EchConscryptPlatform"

  companion object {
    /**
     * Makes this the platform every client built afterwards will use.
     *
     * `Platform.get()` is a process-wide singleton, read when a client builds its socket factory,
     * so a client built before this call keeps the platform it was built with. Pair with
     * [uninstall]; both live here so that this file stays the only one reaching into
     * `okhttp3.internal`.
     */
    fun install() = Platform.resetForTests(EchConscryptPlatform())

    /** Puts back whichever platform OkHttp would have chosen for itself. */
    fun uninstall() = Platform.resetForTests()
  }
}
