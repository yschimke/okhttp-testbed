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
 * Reports whether an ordinary public-API OkHttp client can reach a TLS 1.3 server that permits only
 * the X25519MLKEM768 post-quantum hybrid named group.
 *
 * This is expected to fail on providers that do not offer that group, including the JDKs currently
 * in the testbed matrix. It runs in the non-gating `postQuantumTest` task so the failed handshake is
 * recorded as the capability gap it is. When an OkHttp artifact and its TLS provider can configure
 * or offer X25519MLKEM768, this same test turns green without changing the assertion.
 *
 * [lysine-dev/okhttp#9517](https://github.com/lysine-dev/okhttp/pull/9517) proposes the public
 * `ConnectionSpec` selector for named groups. This test deliberately uses only APIs in published
 * artifacts, so it can record the before-and-after result across the testbed's version matrix.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostQuantumTest {
  private lateinit var client: OkHttpClient

  @BeforeAll
  fun buildClient() {
    val caPem =
      OkHttpClient()
        .newCall(
          Request(
            "http://${server.host}:${server.getMappedPort(TestServer.PLAIN_PORT)}/ca.pem".toHttpUrl(),
          ),
        ).execute()
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
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
        .build()
  }

  @Test
  fun connectsToPostQuantumOnlyServer() {
    val response =
      client
        .newCall(
          Request(
            "https://${server.host}:${server.getMappedPort(TestServer.PQC_PORT)}/health".toHttpUrl(),
          ),
        ).execute()

    response.use {
      assertThat(it.code).isEqualTo(200)
      assertThat(checkNotNull(it.handshake).tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    }
  }

  companion object {
    @Container
    @JvmStatic
    val server: GenericContainer<*> = TestServer.container()
  }
}
