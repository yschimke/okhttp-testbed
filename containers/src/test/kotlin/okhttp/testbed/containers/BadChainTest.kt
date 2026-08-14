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
import assertk.assertions.isNotNull
import java.io.File
import java.io.IOException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * What OkHttp does with a certificate chain it should refuse.
 *
 * The five chains come from `test-server`, which mints them at startup: expired, wrong host,
 * self-signed, untrusted root, and a chain missing its intermediate. Each differs from the good
 * one in exactly one way, so a refusal here names a specific defect rather than "something was
 * wrong with the certificate".
 *
 * This gates, and it should. Nothing third-party is in the loop — the image is built from this
 * repository — and OkHttp accepting any of these would be a genuine defect in the published
 * artifact rather than a fact about somebody's server.
 *
 * The positive control matters as much as the negatives: [goodChainIsAccepted] proves the same
 * client, trusting the same CA, does complete a handshake. Without it a broken fixture would
 * make every assertion below pass for the wrong reason.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BadChainTest {
  private lateinit var client: OkHttpClient

  @BeforeAll
  fun trustTheFixtureCA() {
    // The CA is minted per process, so it has to be fetched rather than pinned — which is also
    // why the plain port is exposed at all. okhttp-tls turns the PEM into a real trust manager;
    // nothing here weakens verification, since verification is the thing under test.
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
  fun goodChainIsAccepted() {
    client.newCall(Request.Builder().url(tlsUrl(GOOD_PORT, "/health")).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }

  @ParameterizedTest
  @ValueSource(ints = [EXPIRED, WRONG_HOST, SELF_SIGNED, UNTRUSTED_ROOT, INCOMPLETE_CHAIN])
  fun badChainIsRefused(port: Int) {
    val failure =
      try {
        client.newCall(Request.Builder().url(tlsUrl(port, "/health")).build()).execute().use {
          null
        }
      } catch (e: IOException) {
        e
      }

    // An SSLException rather than a specific subclass: which one a client reports for a bad
    // chain is the client's business, and pinning it here would turn a change in OkHttp's error
    // reporting into a failure about certificate validation. That the handshake failed at all
    // is the assertion.
    assertThat(failure, name = "${CHAIN_NAMES[port]} must be refused")
      .isNotNull()
      .isInstanceOf(SSLException::class)
  }

  private fun plainUrl(path: String) = "http://${server.host}:${server.getMappedPort(PLAIN_PORT)}$path".toHttpUrl()

  private fun tlsUrl(
    port: Int,
    path: String,
  ) = "https://${server.host}:${server.getMappedPort(port)}$path".toHttpUrl()

  companion object {
    private const val PLAIN_PORT = 8080
    private const val GOOD_PORT = 8443
    private const val EXPIRED = 8420
    private const val WRONG_HOST = 8421
    private const val SELF_SIGNED = 8422
    private const val UNTRUSTED_ROOT = 8423
    private const val INCOMPLETE_CHAIN = 8424

    private val CHAIN_NAMES =
      mapOf(
        EXPIRED to "expired",
        WRONG_HOST to "wrong-host",
        SELF_SIGNED to "self-signed",
        UNTRUSTED_ROOT to "untrusted-root",
        INCOMPLETE_CHAIN to "incomplete-chain",
      )

    // The directory is supplied by the build rather than reached for with a relative path, so
    // this doesn't depend on the working directory a test happens to run in.
    private val TEST_SERVER_DIR: String =
      checkNotNull(System.getProperty("testbed.testServerDir")) {
        "testbed.testServerDir is not set — run these tests through Gradle, which supplies it"
      }

    /**
     * One container for the whole class, which departs from the instance-per-test containers
     * elsewhere in this module.
     *
     * It earns the departure: the server is read-only for these assertions — every test does a
     * handshake and nothing else — and it is built from a Dockerfile rather than pulled, so
     * starting it once per parameterised case would pay that cost six times over for a fixture
     * no test can affect.
     */
    @Container
    @JvmStatic
    val server: GenericContainer<*> =
      GenericContainer(
        ImageFromDockerfile().withFileFromPath(".", File(TEST_SERVER_DIR).toPath()),
      ).withExposedPorts(PLAIN_PORT, GOOD_PORT, EXPIRED, WRONG_HOST, SELF_SIGNED, UNTRUSTED_ROOT, INCOMPLETE_CHAIN)
        .waitingFor(Wait.forHttp("/health").forPort(PLAIN_PORT))
  }
}
