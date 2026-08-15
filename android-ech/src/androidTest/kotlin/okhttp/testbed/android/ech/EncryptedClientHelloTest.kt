/*
 * Copyright (C) 2026 Block, Inc.
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
package okhttp.testbed.android.ech

import android.os.Build
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Encrypted Client Hello, end to end, against the containers `run-ech-test.sh` starts on the
 * host: a DoH resolver that answers HTTPS records carrying an ECH config list, and an origin
 * that reports back whether the handshake it accepted used ECH.
 *
 * Three hostnames, three outcomes. `green` is published with the config the origin holds, so
 * the first handshake is accepted. `retry` is published with a stale config, and the origin
 * offers a retry config when it rejects it, so the client should retry and succeed with ECH.
 * `disabled` is published with a stale config and the origin offers nothing, so the client
 * should fall back to a handshake without ECH rather than fail.
 *
 * Below API 37 the outcome is the same for all three, and asserting it is the reason this suite
 * runs on the older emulators in the workflow's matrix at all: `android.net.ssl.EchConfigList`
 * doesn't exist there, so OkHttp has nowhere to put the config list the resolver handed it, and
 * the only acceptable behaviour is an ordinary handshake to the real name. A version of OkHttp
 * that instead failed the call — or leaked the public name into SNI — would be a regression on
 * every Android release before the newest one, which is where most devices are.
 *
 * Before API 29 there is no run to have: the fixture origin is TLS 1.3 only, which Android
 * gained in API 29, so the handshake can't complete for reasons that have nothing to do with
 * ECH. Those levels skip, and what they still prove is that the library loads and initializes
 * on them — [EchTestRunner] calls `OkHttp.initialize` before any of this.
 */
class EncryptedClientHelloTest {
  /** Whether this device can encrypt a client hello at all. Everything below turns on it. */
  private val echSupported = Build.VERSION.SDK_INT >= ECH_API_LEVEL

  @Test
  fun greenPathAcceptsEncryptedClientHello() {
    val result = fixture().get(GREEN_NAME)
    record("greenPathAcceptsEncryptedClientHello", GREEN_NAME, result.routes)

    assertThat(result.body).contains("\"echAccepted\":$echSupported")
    assertThat(result.body).contains("\"serverName\":\"$GREEN_NAME\"")
  }

  @Test
  fun rejectedConfigIsRetriedWithServerConfig() {
    val result = fixture().get(RETRY_NAME)
    record("rejectedConfigIsRetriedWithServerConfig", RETRY_NAME, result.routes)

    assertThat(result.body).contains("\"echAccepted\":$echSupported")
    assertThat(result.body).contains("\"serverName\":\"$RETRY_NAME\"")
  }

  @Test
  fun rejectedConfigWithoutServerConfigIsRetriedWithoutEch() {
    val result = fixture().get(DISABLED_NAME)
    record("rejectedConfigWithoutServerConfigIsRetriedWithoutEch", DISABLED_NAME, result.routes)

    assertThat(result.body).contains("\"echAccepted\":false")
    assertThat(result.body).contains("\"serverName\":\"$DISABLED_NAME\"")
  }

  private fun record(
    case: String,
    server: String,
    routes: List<Route>,
  ) {
    EchResultReport.record(
      suite = "EncryptedClientHelloTest",
      case = case,
      server = server,
      attempts =
        routes.mapIndexed { index, route ->
          val source =
            when {
              index == 0 -> "dns"
              route.echConfigList != null -> "retry"
              else -> "fallback"
            }
          EchResultReport.Attempt(source, route.echConfigList?.toByteArray())
        },
    )
  }

  private fun fixture(): Fixture {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue("requires the host-side ECH fixtures", arguments.getString("ech") == "true")
    assumeTrue(
      "the ECH fixture origin is TLS 1.3 only, which Android has from API $TLS_13_API_LEVEL",
      Build.VERSION.SDK_INT >= TLS_13_API_LEVEL,
    )
    val dohPort = requireNotNull(arguments.getString("dohPort")).toInt()
    val caCertificate = Base64.decode(requireNotNull(arguments.getString("caCertificate")), Base64.DEFAULT)
    val (sslContext, trustManager) = sslContext(caCertificate)
    return Fixture(dohPort, sslContext, trustManager)
  }

  private class Fixture(
    dohPort: Int,
    sslContext: SSLContext,
    trustManager: X509TrustManager,
  ) {
    private val client: OkHttpClient

    init {
      val bootstrapClient =
        OkHttpClient
          .Builder()
          .sslSocketFactory(sslContext.socketFactory, trustManager)
          .build()
      val dns =
        DnsOverHttps
          .Builder()
          .client(bootstrapClient)
          .url("https://$DOH_NAME:$dohPort/dns-query".toHttpUrl())
          .bootstrapDnsHosts(InetAddress.getByName("127.0.0.1"))
          // HTTPS records, which is where the ECH config list and the origin's port arrive.
          .includeServiceMetadata(true)
          // The fixture resolves to 127.0.0.1, forwarded to the host by `adb reverse`.
          .resolvePrivateAddresses(true)
          .post(true)
          .build()
      client =
        bootstrapClient
          .newBuilder()
          .dns(dns)
          .addNetworkInterceptor(FixtureRouteTagger)
          .build()
    }

    fun get(hostname: String): FixtureResult {
      val call = client.newCall(Request("https://$hostname/".toHttpUrl()))
      return call.execute().use { response ->
        assertThat(response.code).isEqualTo(200)
        FixtureResult(response.body.string(), call.fixtureRoutes.routes.toList())
      }
    }
  }

  private companion object {
    private fun sslContext(caCertificatePem: ByteArray): Pair<SSLContext, X509TrustManager> {
      val certificate =
        CertificateFactory
          .getInstance("X.509")
          .generateCertificate(ByteArrayInputStream(caCertificatePem))
      val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
      keyStore.load(null)
      keyStore.setCertificateEntry("fixture", certificate)
      val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      trustManagerFactory.init(keyStore)
      val trustManager = trustManagerFactory.trustManagers.single() as X509TrustManager
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(null, arrayOf(trustManager), null)
      return sslContext to trustManager
    }

    /** `android.net.ssl.EchConfigList`, and so OkHttp's Android ECH, arrived in API 37. */
    private const val ECH_API_LEVEL = 37

    /** The fixture origin sets `MinVersion: tls.VersionTLS13`, and Android has TLS 1.3 from 29. */
    private const val TLS_13_API_LEVEL = 29

    private const val DOH_NAME = "doh.test"
    private const val GREEN_NAME = "green.secret.test"
    private const val RETRY_NAME = "retry.secret.test"
    private const val DISABLED_NAME = "disabled.secret.test"
  }
}

private data class FixtureResult(
  val body: String,
  val routes: List<Route>,
)

private object FixtureRouteTagger : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    chain.call().fixtureRoutes.routes += chain.connection()!!.route()
    return chain.proceed(chain.request())
  }
}

private val Call.fixtureRoutes: FixtureRoutes
  get() = tag(FixtureRoutes::class) { FixtureRoutes() }

private class FixtureRoutes {
  val routes = mutableListOf<Route>()
}
