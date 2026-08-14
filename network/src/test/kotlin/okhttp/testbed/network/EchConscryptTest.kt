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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isNotNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The same servers [EchTest] calls, reached through a Conscrypt built from `google3-export`
 * instead of through the JDK's TLS stack.
 *
 * [EchTest] fails its `sni=encrypted` assertions on the JVM, and that is a true result: no
 * published TLS stack a JVM can load will encrypt a client hello. This suite exists to say what
 * is missing rather than that something is. It supplies the two pieces the JVM lacks — a
 * Conscrypt with ECH in it, and a network security policy that says ECH is allowed — and leaves
 * everything else to OkHttp. When these pass and [EchTest] doesn't, the difference is the work
 * still to be done, and it is a small and specific piece of work:
 *
 *  * `ConscryptPlatform.configureTlsExtensions` accepts an `echConfigList` and ignores it. It
 *    needs to call `Conscrypt.setEchConfigList`, the way `Android10Platform` does.
 *  * `ConscryptPlatform.getEchRetryConfig` doesn't exist. Android's reads the retry config out
 *    of an `EchConfigMismatchException`; Conscrypt's OpenJDK `Platform` throws away both the
 *    retry configs and the public name, so on the JVM there is nothing to read. That one is a
 *    Conscrypt change, not an OkHttp change, which is why the stale-config case from [EchTest]
 *    has no counterpart here.
 *
 * The Conscrypt this needs is not published anywhere. `conscrypt/build-conscrypt.sh` builds it
 * and the `conscrypt` workflow caches the result as a release; without it every test here skips.
 * See `conscrypt/README.md`.
 */
@RequiresEndpoint(Endpoint.CLOUDFLARE_DOH)
class EchConscryptTest {
  private lateinit var client: OkHttpClient
  private lateinit var dns: EchRecordingDns
  private lateinit var socketFactory: EchSocketFactory

  @BeforeEach
  fun setUp() {
    assumeTrue(ConscryptEch.isSupported) {
      "requires a Conscrypt with ECH. Run conscrypt/fetch-conscrypt.sh."
    }

    val trustManager = EchEnablingTrustManager(ConscryptEch.platformTrustManager())
    val sslContext = ConscryptEch.sslContext(trustManager)

    // Conscrypt for the bootstrap client too, so one TLS stack is under test rather than two.
    // The resolver is addressed by IP, so resolving it doesn't need a resolver.
    val bootstrapClient =
      OkHttpClient
        .Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .build()

    dns =
      EchRecordingDns(
        DnsOverHttps
          .Builder()
          .client(bootstrapClient)
          .url("https://1.1.1.1/dns-query".toHttpUrl())
          .includeServiceMetadata(true)
          .build(),
      )

    socketFactory = EchSocketFactory(sslContext.socketFactory, dns)

    client =
      bootstrapClient
        .newBuilder()
        .sslSocketFactory(socketFactory, trustManager)
        .dns(dns)
        .build()
  }

  @Test
  @RequiresEndpoint(Endpoint.CLOUDFLARE_ECH)
  fun cloudflareAcceptsAnEncryptedClientHello() {
    val body = get("https://cloudflare-ech.com/cdn-cgi/trace")

    assertThat(socketFactory.encryptedHostnames).contains("cloudflare-ech.com")
    assertThat(body).contains("sni=encrypted")
  }

  @Test
  @RequiresEndpoint(Endpoint.TLS_ECH_DEV)
  fun tlsEchDevAcceptsAnEncryptedClientHello() {
    val body = get("https://tls-ech.dev/")

    assertThat(socketFactory.encryptedHostnames).contains("tls-ech.dev")

    // Only the heading identifies the server we reached; every page links to all of the others.
    assertThat(body).contains("<h1>tls-ech.dev</h1>")
    assertThat(body).contains("You are using ECH")
    assertThat(body).doesNotContain("not using ECH")
  }

  @Test
  @RequiresEndpoint(Endpoint.DEFO_IE)
  fun defoIeAcceptsAnEncryptedClientHello() {
    val body = get("https://defo.ie/ech-check.php")

    assertThat(socketFactory.encryptedHostnames).contains("defo.ie")
    assertThat(body).contains("SSL_ECH_STATUS: success")
  }

  /**
   * TLS 1.2 cannot carry ECH, and this name publishes no config list. The point is that the
   * connection still happens: a client that can do ECH must not break the servers that can't.
   */
  @Test
  @RequiresEndpoint(Endpoint.TLS_ECH_DEV)
  fun tls12IsReachedWithoutEch() {
    val body = get("https://tls12.tls-ech.dev/")

    assertThat(body).contains("<h1>tls12.tls-ech.dev</h1>")
    assertThat(body).contains("You are not using ECH")
    assertThat(body).doesNotContain("You are using ECH")
  }

  /**
   * The config list still has to reach OkHttp for any of this to be OkHttp's ECH rather than the
   * suite's. This asserts the half that already works, on the same connection as the rest.
   */
  @Test
  @RequiresEndpoint(Endpoint.CLOUDFLARE_ECH)
  fun okHttpResolvesTheConfigListFromDns() {
    get("https://cloudflare-ech.com/cdn-cgi/trace")

    assertThat(dns["cloudflare-ech.com"]).isNotNull()
  }

  private fun get(url: String): String =
    client.newCall(Request(url.toHttpUrl())).execute().use { it.body.string() }
}
