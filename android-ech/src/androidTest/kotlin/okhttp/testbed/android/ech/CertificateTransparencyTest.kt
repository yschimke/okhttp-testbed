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
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Android's Certificate Transparency enforcement against a deterministic local fixture.
 *
 * The two names are served by the same endpoint with the same unlogged certificate. The network
 * security config opts one name out and enforces CT for the other (explicitly on API 36, by the
 * Android 17 default on API 37+). Consequently the opt-out request is a control for every ordinary
 * cause of a TLS failure (the CA, validity, hostname, protocol, and route), while the enforced
 * request must fail specifically at the CT policy check. This avoids relying on
 * `no-sct.badssl.com`, whose certificate can expire or whose behavior can change independently of
 * this repository.
 *
 * CT is available from API 36 and is enabled by default for apps targeting API 37. The qualified
 * network configs make the same test cover both policies if an API 36 device is in the matrix.
 */
class CertificateTransparencyTest {
  private lateinit var client: OkHttpClient

  @Before
  fun setUp() {
    assumeTrue("Certificate Transparency requires Android API 36", Build.VERSION.SDK_INT >= 36)

    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue("requires the host-side TLS fixture", arguments.getString("ct") == "true")
    val caCertificate = Base64.decode(requireNotNull(arguments.getString("caCertificate")), Base64.DEFAULT)
    val (sslContext, trustManager) = sslContext(caCertificate)
    client =
      OkHttpClient
        .Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        .build()
  }

  @Test
  fun unloggedCertificateConnectsWhenCtIsOptedOut() {
    get(CT_OPT_OUT_NAME).use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.body.string()).contains("\"serverName\":\"$CT_OPT_OUT_NAME\"")
    }
  }

  @Test
  fun unloggedCertificateIsRejectedWhenCtIsEnforced() {
    val failure =
      try {
        get(CT_ENFORCED_NAME).use { response ->
          fail("expected CT enforcement, but received HTTP ${response.code}")
        }
        error("unreachable")
      } catch (e: SSLHandshakeException) {
        e
      }

    val messages =
      generateSequence<Throwable>(failure) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString("\n")
    assertThat(messages).contains("Certificate chain does not conform to required transparency policy.")
  }

  private fun get(hostname: String) =
    client
      .newCall(Request.Builder().url("https://$hostname:$FIXTURE_PORT/").build())
      .execute()

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

    private const val FIXTURE_PORT = 8443
    private const val CT_ENFORCED_NAME = "ct-enforced.test"
    private const val CT_OPT_OUT_NAME = "ct-opt-out.test"
  }
}
