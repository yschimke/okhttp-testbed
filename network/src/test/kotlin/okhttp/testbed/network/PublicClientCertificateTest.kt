/*
 * Copyright (C) 2026 Square, Inc.
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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Mutual TLS against a server this repository does not run.
 *
 * `ClientCertificateTest` covers the same ground against the fixture, where the failure modes can
 * be made exactly. This is the reality check: `client.badssl.com` asks for a certificate the way a
 * real deployment does, and answers `400` rather than failing the handshake when none arrives —
 * which is itself worth knowing, because it means "my client certificate is not configured" can
 * reach an application as an HTTP status rather than as a TLS error.
 *
 * The identity is badssl's own, published for this purpose and fetched at run time. The password
 * is `badssl.com` and is on their site; it protects nothing.
 *
 * A `KeyStore` and a `KeyManagerFactory` rather than `okhttp-tls`: the credential is distributed
 * as PKCS#12, `HandshakeCertificates` has no route in from one, and converting it would mean
 * testing the conversion. Raw JDK APIs are the honest way to load what the world actually ships.
 */
class PublicClientCertificateTest {
  @Test
  fun theClientCertificateIsAcceptedAndOmittingItIsNot() {
    assumeAvailable(Endpoint.BADSSL_CLIENT)

    val identified = clientWithIdentity()

    val accepted = codeFrom(identified, "with a client certificate")

    assertThat(accepted, name = "with badssl's published client certificate").isEqualTo(200)

    // The same request with nothing to present. `client.badssl.com` requests rather than requires,
    // so this is an HTTP answer and not a handshake failure — the fixture suite covers the case
    // where the server insists.
    val refused = codeFrom(plainClient(), "without a client certificate")

    assertThat(refused, name = "with no client certificate").isNotEqualTo(200)
  }

  /**
   * The identity survives a second, genuinely new connection.
   *
   * The pool is emptied in between, so the second request performs its own handshake. Key material
   * read once and cached, or a `KeyManager` consulted only for a cold client, passes the case
   * above and fails here.
   */
  @Test
  fun theCertificateIsPresentedOnASecondConnection() {
    assumeAvailable(Endpoint.BADSSL_CLIENT)

    val client = clientWithIdentity()

    assertThat(codeFrom(client, "first connection"), name = "first connection").isEqualTo(200)
    client.connectionPool.evictAll()
    assertThat(codeFrom(client, "second connection"), name = "second connection").isEqualTo(200)
  }

  /**
   * The status, or a skip if badssl did not answer in time.
   *
   * A read timeout is not a result about client certificates; it is a result about badssl.com's
   * bandwidth, which this suite has already watched vary. Failing on it would put an outage in a
   * column meant for TLS behaviour — the same reason the preflight exists, arriving after the
   * preflight has already passed.
   */
  private fun codeFrom(
    client: OkHttpClient,
    what: String,
  ): Int =
    try {
      client.newCall(Request.Builder().url(CLIENT_URL).build()).execute().use { it.code }
    } catch (e: SocketTimeoutException) {
      assumeTrue(false) { "client.badssl.com timed out $what: ${e.message}" }
      error("unreachable")
    }

  /** Generous, because badssl.com is slow rather than broken: the default 10s is not enough. */
  private fun plainClient() =
    OkHttpClient
      .Builder()
      .callTimeout(TIMEOUT)
      .readTimeout(TIMEOUT)
      .build()

  private fun clientWithIdentity(): OkHttpClient {
    val pkcs12 =
      OkHttpClient()
        .newCall(Request.Builder().url(IDENTITY_URL).build())
        .execute()
        .use { response ->
          assumeTrue(response.code == 200) { "badssl's client identity is not being served: HTTP ${response.code}" }
          response.body.bytes()
        }

    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(ByteArrayInputStream(pkcs12), PASSWORD.toCharArray())

    val keyManagers =
      KeyManagerFactory
        .getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore, PASSWORD.toCharArray()) }
        .keyManagers

    // The platform's own trust store: badssl's server chain is publicly trusted, and swapping in
    // anything else here would be testing the trust manager rather than the client certificate.
    val trustManagers =
      TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers

    val context = SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, null) }

    return plainClient()
      .newBuilder()
      .sslSocketFactory(context.socketFactory, trustManagers[0] as X509TrustManager)
      .build()
  }

  private fun assumeAvailable(endpoint: Endpoint) {
    val result = Preflight.check(endpoint)
    assumeTrue(result.up) { "${endpoint.server} is unavailable: ${result.detail}" }
  }

  private companion object {
    const val CLIENT_URL = "https://client.badssl.com/"

    /** Published by badssl for exactly this, along with the password below. */
    const val IDENTITY_URL = "https://badssl.com/certs/badssl.com-client.p12"
    const val PASSWORD = "badssl.com"

    val TIMEOUT: Duration = Duration.ofSeconds(30)
  }
}
