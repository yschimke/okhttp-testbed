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
package okhttp.testbed.containers

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The extensions OkHttp's ClientHello carried, as `test-server` saw them.
 *
 * How's My SSL answers with suites, groups and signature algorithms, which is most of a
 * ClientHello but not the part a CDN keys on hardest: the extension list and its order is what a
 * JA3 or JA4 fingerprint is largely computed from. `test-server` reports it from
 * `crypto/tls`'s own view of the offer, so the record is available without a third party.
 *
 * The GREASE question is asked here too. A client that supports ECH is meant to send the
 * `encrypted_client_hello` extension even when it has no configuration for the name, so that
 * using ECH and not using it look the same on the wire — which means whether the extension was
 * offered at all is visible, and whether it was real is deliberately not. That is recorded,
 * never asserted: today's JVM has no ECH and offers nothing, and pinning that would turn the
 * feature arriving into a failure.
 *
 * What *is* asserted is the fixture's own consistency, which is a fact about this repository
 * rather than about the platform: the list is recorded at all, it carries the two extensions no
 * TLS 1.3 handshake can omit, and the ECH flag agrees with the list it was derived from.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientHelloExtensionsTest {
  private lateinit var client: OkHttpClient

  @BeforeAll
  fun trustTheFixtureCA() {
    val caPem =
      OkHttpClient()
        .newCall(Request.Builder().url(plainUrl("/ca.pem")).build())
        .execute()
        .use { response ->
          check(response.code == 200) { "the fixture CA is not being served: HTTP ${response.code}" }
          response.body.string()
        }

    val certificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(caPem.decodeCertificatePem())
        .build()

    client =
      OkHttpClient
        .Builder()
        .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
        .build()
  }

  @Test
  fun theOfferCarriesItsExtensionList() {
    val extensions = report().extensions()

    assertThat(extensions, name = "extensions offered").isNotEmpty()

    // Two a TLS 1.3 ClientHello cannot do without: the name being requested, and the version
    // list, since 1.3 is negotiated through supported_versions rather than the record header.
    // Anything beyond these is the platform's business and is recorded rather than required.
    assertThat(extensions, name = "extensions offered").contains("server_name")
    assertThat(extensions, name = "extensions offered").contains("supported_versions")
  }

  /**
   * The flag is derived from the list, so the two disagreeing means the server's own reporting
   * is wrong — the one thing here that would make the GREASE record untrustworthy without
   * looking untrustworthy.
   */
  @Test
  fun theEchFlagAgreesWithTheExtensionList() {
    val body = report()

    val offered = Regex("\"encryptedClientHelloOffered\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1)

    assertThat(offered, name = "encryptedClientHelloOffered")
      .isEqualTo(body.extensions().contains("encrypted_client_hello").toString())
  }

  /** The `/tls` body, raw. Two fields do not justify a JSON dependency — as in `ClientHelloTest`. */
  private fun report(): String =
    client.newCall(Request.Builder().url(tlsUrl("/tls")).build()).execute().use { response ->
      check(response.code == 200) { "/tls answered HTTP ${response.code}" }
      response.body.string()
    }

  private fun String.extensions(): List<String> {
    val array = Regex("\"extensions\"\\s*:\\s*\\[([^\\]]*)]").find(this)?.groupValues?.get(1).orEmpty()
    return Regex("\"([^\"]*)\"").findAll(array).map { it.groupValues[1] }.toList()
  }

  private fun plainUrl(path: String) = "http://${server.host}:${server.getMappedPort(TestServer.PLAIN_PORT)}$path".toHttpUrl()

  private fun tlsUrl(path: String) = "https://${server.host}:${server.getMappedPort(TestServer.TLS_PORT)}$path".toHttpUrl()

  companion object {
    /** One container for the class: every case here reads, and none can affect the next. */
    @Container
    @JvmStatic
    val server: GenericContainer<*> = TestServer.container()
  }
}
