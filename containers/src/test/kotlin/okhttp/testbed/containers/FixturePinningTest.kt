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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.decodeCertificatePem
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Pinning that *succeeds*, against a chain this repository controls.
 *
 * `PinningTest` covers the negative case in public, where a wrong pin is wrong however the world
 * changes. The positive case cannot live there: pinning a live public chain means pinning
 * something that rotates, and a suite that goes red when Let's Encrypt renews a certificate is a
 * suite everyone learns to ignore. Here the CA is minted by the fixture at startup, so the pin is
 * computed from the chain in front of us and is correct by construction.
 *
 * It gates. Nothing third-party is in the loop, and a `CertificatePinner` that rejected a
 * certificate it had just been given the pin for would be a defect in the published artifact.
 *
 * The fixture chain also answers the Certificate Transparency question by existing: a privately
 * issued certificate carries no SCTs, and it connects — which is OkHttp enforcing none, as
 * documented. `PinningTest` records the public baseline so the two can be read together.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixturePinningTest {
  private lateinit var certificates: HandshakeCertificates

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

    certificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(caPem.decodeCertificatePem())
        .build()
  }

  @Test
  fun theRightPinIsAccepted() {
    // The pin is taken from the handshake rather than written down: the fixture mints a fresh CA
    // per container, so any constant here would be wrong on the second run.
    val leaf =
      trustingClient()
        .newCall(Request.Builder().url(tlsUrl("/health")).build())
        .execute()
        .use { response ->
          val handshake = checkNotNull(response.handshake) { "no handshake against the TLS listener" }
          handshake.peerCertificates.first() as X509Certificate
        }
    val leafPin = CertificatePinner.pin(leaf)

    val client =
      trustingClient()
        .newBuilder()
        .certificatePinner(
          CertificatePinner
            .Builder()
            .add(server.host, leafPin)
            .build(),
        ).build()

    client.newCall(Request.Builder().url(tlsUrl("/health")).build()).execute().use { response ->
      assertThat(response.code, name = "pinned to the leaf it was given").isEqualTo(200)
    }
  }

  /**
   * A pin belonging to some other certificate is refused.
   *
   * The control for the case above. Without it, a `CertificatePinner` that had stopped checking
   * anything at all would pass [theRightPinIsAccepted] just as happily.
   *
   * The wrong pin is a real pin of a real certificate, minted here and used nowhere. An earlier
   * version edited the last character of the correct one instead, which CI caught and a local run
   * did not — because it only collides *sometimes*. A pin is base64 of 32 bytes, so its final
   * character carries four significant bits and two of padding; swapping `A=` for `B=` changes
   * only padding and decodes to the identical hash. The fixture mints a fresh certificate per
   * container, so that landed about one run in sixteen: a test that would have looked like a
   * mystery flake for as long as anyone was willing to re-run it.
   *
   * Deriving a "different" pin from a correct one is the trap. Taking one from a different
   * certificate cannot collide.
   */
  @Test
  fun aPinForADifferentCertificateIsRefused() {
    val wrong = CertificatePinner.pin(HeldCertificate.Builder().build().certificate)

    val client =
      trustingClient()
        .newBuilder()
        .certificatePinner(CertificatePinner.Builder().add(server.host, wrong).build())
        .build()

    val failure =
      try {
        client.newCall(Request.Builder().url(tlsUrl("/health")).build()).execute().use {
          throw AssertionError("a pin for another certificate was accepted: HTTP ${it.code}")
        }
      } catch (e: IOException) {
        e
      }

    assertThat(failure, name = "a pin that is not this leaf's").isInstanceOf(SSLPeerUnverifiedException::class)
  }

  /**
   * A chain with no SCTs connects, which is the Certificate Transparency answer.
   *
   * The fixture CA is private, so nothing it issues is logged and no SCT exists to check. That the
   * handshake completes is OkHttp enforcing no CT policy today. This records rather than asserts:
   * a platform quietly starting to enforce it is the result this table exists to reveal, not a
   * reason to stop the rest of the container suite.
   */
  @Test
  fun chainWithoutSctsBehaviourIsRecorded() {
    val result =
      try {
        trustingClient().newCall(Request.Builder().url(tlsUrl("/health")).build()).execute().use { response ->
          TlsPolicyReport.Check(accepted = true, detail = "HTTP ${response.code}; private CA, no SCT")
        }
      } catch (e: IOException) {
        TlsPolicyReport.Check(accepted = false, detail = "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
      }

    TlsPolicyReport.record(result)
  }

  private fun trustingClient() =
    OkHttpClient
      .Builder()
      .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
      .build()

  private fun plainUrl(path: String) = "http://${server.host}:${server.getMappedPort(TestServer.PLAIN_PORT)}$path".toHttpUrl()

  private fun tlsUrl(path: String) = "https://${server.host}:${server.getMappedPort(TestServer.TLS_PORT)}$path".toHttpUrl()

  companion object {
    /** One container for the class: every case reads, and the pin is taken from it once. */
    @Container
    @JvmStatic
    val server: GenericContainer<*> = TestServer.container()
  }
}
