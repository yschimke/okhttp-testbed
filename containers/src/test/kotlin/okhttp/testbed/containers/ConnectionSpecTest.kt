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
import assertk.assertions.isNotNull
import java.io.IOException
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * What a `ConnectionSpec` actually promises, against a server pinned to one version per port.
 *
 * `ConnectionSpec` is documentation that the client is expected to enforce: `RESTRICTED_TLS` says
 * it refuses everything before 1.2, `MODERN_TLS` says something looser, and both are worth
 * checking against a server that will genuinely offer only what it says. `test-server` runs a
 * listener per version for exactly this, so a refusal here is the spec refusing rather than two
 * ends failing to find common ground by accident.
 *
 * Two platforms are in the loop and only one of them is OkHttp, which the obsolete-version cases
 * have to be honest about. Modern JDKs disable TLS 1.0 and 1.1 through `jdk.tls.disabledAlgorithms`
 * regardless of what any spec permits, so the client never offers them — and the failure that
 * comes back is `Received fatal alert: protocol_version` from the *server*, rejecting an offer
 * that was too new for a listener pinned to 1.0. Measured rather than assumed: even
 * `COMPATIBLE_TLS`, which permits the old versions, cannot reach those listeners on this JDK.
 *
 * So those cases assert the refusal and nothing about its author. Claiming "RESTRICTED_TLS
 * refused TLS 1.0" would credit the client library for the platform's work, and would keep
 * passing on a JDK where the spec had stopped doing anything at all.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionSpecTest {
  private lateinit var certificates: HandshakeCertificates

  @BeforeAll
  fun trustTheFixtureCA() {
    val caPem =
      OkHttpClient()
        .newCall(
          Request
            .Builder()
            .url("http://${server.host}:${server.getMappedPort(TestServer.PLAIN_PORT)}/ca.pem")
            .build(),
        ).execute()
        .use { response ->
          check(response.code == 200) { "the fixture CA is not being served: HTTP ${response.code}" }
          response.body.string()
        }

    certificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(caPem.decodeCertificatePem())
        .build()
  }

  /**
   * The modern versions connect under the strictest spec, and report themselves honestly.
   *
   * `Handshake.tlsVersion()` has to name the version the listener pins, or the fixture and the
   * client disagree about what just happened — which would make every other case here unreadable.
   * The negotiated suite also has to be one the spec offered: a suite from outside the list would
   * mean the spec was advisory rather than applied.
   */
  @Test
  fun restrictedTlsConnectsToTls12() {
    val handshake = handshake(TestServer.TLS12_PORT, ConnectionSpec.RESTRICTED_TLS)

    assertThat(handshake.tlsVersion, name = "negotiated version").isEqualTo(TlsVersion.TLS_1_2)
    assertThat(
      ConnectionSpec.RESTRICTED_TLS.cipherSuites,
      name = "the negotiated suite is one RESTRICTED_TLS offered",
    ).isNotNull().contains(handshake.cipherSuite)
  }

  @Test
  fun restrictedTlsConnectsToTls13() {
    val handshake = handshake(TestServer.TLS13_PORT, ConnectionSpec.RESTRICTED_TLS)

    assertThat(handshake.tlsVersion, name = "negotiated version").isEqualTo(TlsVersion.TLS_1_3)
  }

  /**
   * The obsolete versions are unreachable. See the note on the class about who does the refusing.
   */
  @Test
  fun restrictedTlsRefusesTls10() {
    assertThat(failureAgainst(TestServer.TLS10_PORT, ConnectionSpec.RESTRICTED_TLS), name = "TLS 1.0")
      .isNotNull()
  }

  @Test
  fun restrictedTlsRefusesTls11() {
    assertThat(failureAgainst(TestServer.TLS11_PORT, ConnectionSpec.RESTRICTED_TLS), name = "TLS 1.1")
      .isNotNull()
  }

  /**
   * `MODERN_TLS` reaches the versions it documents.
   *
   * Both listeners, in one case, because the interesting property is that the looser spec reaches
   * *both* current versions rather than settling on one. Each listener pins its version, so the
   * expected answer is exact rather than a range.
   */
  @Test
  fun modernTlsReachesBothCurrentVersions() {
    val versions =
      listOf(TestServer.TLS12_PORT, TestServer.TLS13_PORT).map {
        handshake(it, ConnectionSpec.MODERN_TLS).tlsVersion
      }

    assertThat(versions[0], name = "MODERN_TLS on the 1.2 listener").isEqualTo(TlsVersion.TLS_1_2)
    assertThat(versions[1], name = "MODERN_TLS on the 1.3 listener").isEqualTo(TlsVersion.TLS_1_3)
  }

  /**
   * A spec with nothing in common with the server fails, and says something.
   *
   * The failure mode worth guarding against is a bare `SocketException: connection reset` — true,
   * useless, and indistinguishable from the server having gone away. A spec pinned to 1.3 against
   * a listener pinned to 1.2 has an empty intersection by construction, so this is the cleanest
   * possible version of the question.
   */
  @Test
  fun anEmptyIntersectionFailsWithSomethingToRead() {
    val onlyTls13 =
      ConnectionSpec
        .Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_3)
        .build()

    val failure = failureAgainst(TestServer.TLS12_PORT, onlyTls13)

    assertThat(failure, name = "1.3-only spec against a 1.2-only listener").isNotNull()
    // Not the exact wording, which is the platform's. That there is any wording at all is the
    // assertion: a caller with an empty message cannot tell this from a network failure.
    assertThat(failure!!.message.orEmpty().isNotEmpty(), name = "the failure explains itself").isEqualTo(true)
  }

  private fun clientFor(spec: ConnectionSpec) =
    OkHttpClient
      .Builder()
      .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
      .connectionSpecs(listOf(spec))
      .build()

  private fun handshake(
    port: Int,
    spec: ConnectionSpec,
  ) = clientFor(spec)
    .newCall(Request.Builder().url(urlFor(port)).build())
    .execute()
    .use { response ->
      check(response.code == 200) { "port $port answered HTTP ${response.code}" }
      checkNotNull(response.handshake) { "port $port completed without a handshake" }
    }

  private fun failureAgainst(
    port: Int,
    spec: ConnectionSpec,
  ): IOException? =
    try {
      clientFor(spec).newCall(Request.Builder().url(urlFor(port)).build()).execute().use {
        throw AssertionError("port $port accepted a connection it should have refused: HTTP ${it.code}")
      }
    } catch (e: IOException) {
      e
    }

  private fun urlFor(port: Int) = "https://${server.host}:${server.getMappedPort(port)}/health".toHttpUrl()

  companion object {
    /** One container for the class: every case here reads, and none can affect the next. */
    @Container
    @JvmStatic
    val server: GenericContainer<*> = TestServer.container()
  }
}
