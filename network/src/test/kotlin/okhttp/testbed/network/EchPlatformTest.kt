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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [EchTest], with [EchConscryptPlatform] installed.
 *
 * The requests, the resolver and the assertions are [EchTest]'s. The client is built the same
 * way, from public API, with nothing configured on it — no socket factory, no trust manager, no
 * interception of the handshake. The single difference is which `Platform` OkHttp finds when it
 * builds that client, and the single difference between the platforms is one call to
 * `Conscrypt.setEchConfigList`.
 *
 * Which makes the pair the measurement. [EchTest] fails its `sni=encrypted` assertions and this
 * passes them; everything else about the two runs is the same, so the difference between them is
 * that call and nothing else. [EchConscryptTest] answers a nearby but weaker question — it
 * reaches the same servers through a socket factory of its own, so it shows Conscrypt can do ECH
 * without showing that OkHttp's own path would carry it.
 *
 * Two of [EchTest]'s cases are deliberately not here. `echIsRetriedOnStaleTlsEchDev` and
 * `tlsIsNotUsedOnTls12TlsEchDev` both need a server's rejection to be read back and retried, and
 * that needs `SSL_get0_ech_retry_configs`, which Conscrypt exposes on Android and not on the JVM.
 * No platform written here can supply it. [EchConscryptTest] already records that gap; repeating
 * it in a third suite would report one missing feature three times.
 */
@RequiresEndpoint(Endpoint.CLOUDFLARE_DOH)
class EchPlatformTest {
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    assumeTrue(ConscryptEch.isSupported) {
      "requires a Conscrypt with ECH. Run conscrypt/fetch-conscrypt.sh."
    }

    // Before the clients are built: a client keeps the platform it was built with.
    EchConscryptPlatform.install()

    val bootstrapClient = OkHttpClient()

    val dns =
      DnsOverHttps
        .Builder()
        .client(bootstrapClient)
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .includeServiceMetadata(true)
        .build()

    client =
      bootstrapClient
        .newBuilder()
        .addNetworkInterceptor(RouteTagger)
        .dns(dns)
        .build()
  }

  @AfterEach
  fun tearDown() {
    EchConscryptPlatform.uninstall()
  }

  @Test
  @RequiresEndpoint(Endpoint.CLOUDFLARE_ECH)
  fun cloudflareUsesEch() {
    val call = client.newCall(Request("https://cloudflare-ech.com/cdn-cgi/trace".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("sni=encrypted")
    }
  }

  @Test
  @RequiresEndpoint(Endpoint.TLS_ECH_DEV)
  fun echIsAcceptedOnTlsEchDev() {
    val call = client.newCall(Request("https://tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()

      // Only the heading identifies the server we reached; every page links to all of the others.
      assertThat(body).contains("<h1>tls-ech.dev</h1>")
      assertThat(body).contains("You are using ECH")
      assertThat(body).doesNotContain("not using ECH")
    }
  }

  @Test
  @RequiresEndpoint(Endpoint.DEFO_IE)
  fun echIsAcceptedOnDefoIe() {
    val call = client.newCall(Request("https://defo.ie/ech-check.php".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("SSL_ECH_STATUS: success")
    }
  }
}
