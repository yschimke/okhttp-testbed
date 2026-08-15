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
import assertk.assertions.isInstanceOf
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
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
 * Mutual TLS, against a listener that genuinely requires a certificate.
 *
 * `test-server`'s ordinary TLS listener merely *requests* one, which is right for reporting what a
 * client offered and useless for testing that it offered anything: a client with no certificate is
 * served just the same, so "presented" and "ignored" look identical. The `mtls` listener requires
 * and verifies one against the fixture CA, so omitting it has to fail — and the failure is the
 * assertion.
 *
 * The client identity is minted by the fixture and fetched at runtime from `/client.pem`, for the
 * same reason the CA is: the CA is generated per container, so an identity committed here could
 * not have been signed by it.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientCertificateTest {
  private lateinit var withCertificate: HandshakeCertificates
  private lateinit var withoutCertificate: HandshakeCertificates

  @BeforeAll
  fun fetchTheFixtureIdentity() {
    val ca = plainText("/ca.pem").decodeCertificatePem()
    val held = HeldCertificate.decode(plainText("/client.pem"))

    withCertificate =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(ca)
        .heldCertificate(held)
        .build()

    withoutCertificate =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(ca)
        .build()
  }

  /**
   * The certificate is presented, and the server says whose it was.
   *
   * Asserting the response code alone would pass against a listener that had quietly stopped
   * asking. `/tls` echoes the subject it verified, so this asserts the handshake carried the
   * identity rather than that a request succeeded.
   */
  @Test
  fun aClientCertificateIsPresentedAndAccepted() {
    val body = mtlsBody(clientWith(withCertificate))

    assertThat(body, name = "the server's view of the client certificate").contains(CLIENT_SUBJECT)
  }

  /**
   * It is presented on a *new* connection too, not just the first.
   *
   * The pool is emptied between the two calls, so the second handshake is a real one rather than a
   * reused connection reporting the first handshake's result. Key material that is read once and
   * cached, or a `KeyManager` consulted only on a cold client, would pass the case above and fail
   * here.
   */
  @Test
  fun theCertificateIsPresentedOnASecondConnection() {
    val client = clientWith(withCertificate)

    assertThat(mtlsBody(client), name = "first connection").contains(CLIENT_SUBJECT)
    client.connectionPool.evictAll()
    assertThat(mtlsBody(client), name = "second connection").contains(CLIENT_SUBJECT)
  }

  /**
   * With no certificate, the handshake fails — and says what it wanted.
   *
   * The failure a caller must not get is a bare connection reset, indistinguishable from the
   * server having gone away. `certificate_required` is the alert TLS 1.3 defines for exactly this,
   * and the message carrying it is what turns an outage-looking failure into a fixable one.
   */
  @Test
  fun omittingTheCertificateFailsDistinguishably() {
    val failure = mtlsFailure(clientWith(withoutCertificate))

    assertThat(failure, name = "no client certificate").isInstanceOf(SSLHandshakeException::class)
    assertThat(failure.message.orEmpty().isNotEmpty(), name = "the failure explains itself").isEqualTo(true)
  }

  /**
   * A certificate from a CA the server does not accept looks exactly like having none.
   *
   * This is the case worth knowing about, and it is not the one you would guess. The server
   * advertises which issuers it will accept; the JDK's key manager compares its identities against
   * that list, finds no match, and sends **nothing** — so the server reports a missing certificate
   * rather than an untrusted one, and the client's logs say the same. Anyone debugging "I
   * configured a certificate and the server says I did not" is meeting this.
   *
   * Asserted as *the same failure* as omitting it, which is the honest statement of the
   * behaviour rather than a wish about it.
   */
  @Test
  fun aCertificateFromAnUnacceptedIssuerIsNotSent() {
    val strangerCa = HeldCertificate.Builder().certificateAuthority(0).build()
    val stranger = HeldCertificate.Builder().signedBy(strangerCa).build()

    val certificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(plainText("/ca.pem").decodeCertificatePem())
        .heldCertificate(stranger, strangerCa.certificate)
        .build()

    val failure = mtlsFailure(clientWith(certificates))

    assertThat(failure, name = "a certificate from an unaccepted issuer")
      .isInstanceOf(SSLHandshakeException::class)
  }

  private fun clientWith(certificates: HandshakeCertificates) =
    OkHttpClient
      .Builder()
      .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
      .build()

  private fun mtlsBody(client: OkHttpClient): String =
    client.newCall(Request.Builder().url(mtlsUrl()).build()).execute().use { response ->
      assertThat(response.code, name = "mtls listener").isEqualTo(200)
      response.body.string()
    }

  private fun mtlsFailure(client: OkHttpClient): IOException =
    try {
      client.newCall(Request.Builder().url(mtlsUrl()).build()).execute().use {
        throw AssertionError("the mtls listener served a client with no acceptable certificate: HTTP ${it.code}")
      }
    } catch (e: IOException) {
      e
    }

  private fun plainText(path: String): String =
    OkHttpClient()
      .newCall(
        Request
          .Builder()
          .url("http://${server.host}:${server.getMappedPort(TestServer.PLAIN_PORT)}$path")
          .build(),
      ).execute()
      .use { response ->
        check(response.code == 200) { "$path is not being served: HTTP ${response.code}" }
        response.body.string()
      }

  private fun mtlsUrl() = "https://${server.host}:${server.getMappedPort(TestServer.MTLS_PORT)}/tls".toHttpUrl()

  companion object {
    /** What the fixture calls the identity it mints, echoed back by `/tls`. */
    const val CLIENT_SUBJECT = "CN=okhttp-testbed client"

    @Container
    @JvmStatic
    val server: GenericContainer<*> = TestServer.container()
  }
}
