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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * An ordinary client, against servers that speak ECH.
 *
 * This is the case that breaks real users, and it has nothing to do with ECH working: a client
 * that supports ECH but has no configuration for a name is meant to send a GREASE extension
 * anyway, so that using ECH and not using it look the same on the wire. A middlebox that objects
 * to the extension breaks every such handshake — and the client involved was not trying to use
 * ECH at all.
 *
 * So the assertion is deliberately unglamorous: with nothing configured, the request succeeds.
 * It runs on **every** platform rather than only where ECH works, which is the point — the JVM
 * cannot do ECH today, and that must not stop it talking to servers that can.
 *
 * Each case also asks the server whether ECH was used, and requires the answer to be no. Without
 * that, a success here would be ambiguous: a run where ECH quietly started working would pass
 * while testing something else entirely.
 */
class EchGreaseTest {
  @ParameterizedTest
  @EnumSource(EchServer::class)
  fun unconfiguredClientIsServed(server: EchServer) {
    val result = Preflight.check(server.endpoint)
    assumeTrue(result.up) { "${server.endpoint.server} is unavailable: ${result.detail}" }

    // A default client: no ECH configuration, no DoH, nothing arranged. What an application
    // that has never heard of ECH would send.
    val response =
      OkHttpClient()
        .newCall(Request.Builder().url(server.url).build())
        .execute()

    val body = response.use { it.code to it.body.string() }

    assertThat(body.first, name = "${server.endpoint.server} status").isEqualTo(200)
    assertThat(body.second, name = "${server.endpoint.server} says ECH was not used")
      .contains(server.notUsed)
  }

  /**
   * The public servers that speak ECH, and how each says it did not.
   *
   * The marker is the server's own words rather than a header we impose, so a server that
   * changed its wording fails loudly here instead of quietly asserting nothing.
   */
  enum class EchServer(
    val endpoint: Endpoint,
    val url: String,
    val notUsed: String,
  ) {
    CLOUDFLARE(
      endpoint = Endpoint.CLOUDFLARE_ECH,
      url = "https://cloudflare-ech.com/cdn-cgi/trace",
      notUsed = "sni=plaintext",
    ),

    TLS_ECH_DEV(
      endpoint = Endpoint.TLS_ECH_DEV,
      url = "https://tls-ech.dev/",
      notUsed = "You are not using ECH",
    ),

    DEFO_IE(
      endpoint = Endpoint.DEFO_IE,
      url = "https://defo.ie/ech-check.php",
      notUsed = "SSL_ECH_STATUS: not attempted",
    ),
  }
}
