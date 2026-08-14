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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
 * Two names, one certificate, one address — and one connection.
 *
 * HTTP/2 connection coalescing is the optimisation that pays for itself on every page load, and
 * the one most likely to break quietly: coalescing where it should not is a security problem, and
 * failing to coalesce at all is invisible except as latency nobody attributes to it.
 *
 * It lives here rather than in the `network` module because the conditions have to be *made*
 * rather than hoped for. The obvious public candidates do not qualify: `cloudflare.com` and
 * `www.cloudflare.com` share a certificate but resolve to different edge addresses, so they
 * cannot coalesce however well the client behaves — checked, rather than assumed, before this was
 * written this way. A container has one address by construction, and `test-server` will mint a
 * certificate covering whatever names it is asked for.
 *
 * The names resolve through a [Dns] that answers with the container's address, which is what
 * makes them two names rather than two spellings of one. Nothing about the certificate is
 * weakened: the fixture CA is trusted properly, and the leaf genuinely carries both names.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Http2CoalescingTest {
  private lateinit var client: OkHttpClient

  private val connections = ConcurrentHashMap<String, MutableSet<Int>>()

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

    val certificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(caPem.decodeCertificatePem())
        .build()

    val containerAddress = InetAddress.getByName(server.host)

    client =
      OkHttpClient
        .Builder()
        .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
        .dns(
          object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
              when (hostname) {
                ALPHA, BETA -> listOf(containerAddress)
                else -> Dns.SYSTEM.lookup(hostname)
              }
          },
        ).eventListener(
          object : EventListener() {
            override fun connectionAcquired(
              call: Call,
              connection: Connection,
            ) {
              connections
                .getOrPut(call.request().url.host) { ConcurrentHashMap.newKeySet() }
                .add(System.identityHashCode(connection))
            }
          },
        ).build()
  }

  @Test
  fun twoNamesOnOneCertificateShareAConnection() {
    val protocols = listOf(ALPHA, BETA).map { name -> get(name) }

    // Coalescing is an HTTP/2 feature and nothing else. If the fixture negotiated HTTP/1.1 the
    // assertion below would be about connection pooling instead, and would quietly mean nothing.
    assertThat(protocols, name = "negotiated protocols").isEqualTo(listOf(Protocol.HTTP_2, Protocol.HTTP_2))

    val used = connections.values.flatten().toSet()
    assertThat(used, name = "connections used by $ALPHA and $BETA").hasSize(1)
  }

  private fun get(hostname: String): Protocol {
    val url = "https://$hostname:${server.getMappedPort(TestServer.TLS_PORT)}/health".toHttpUrl()
    return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      check(response.code == 200) { "$hostname answered HTTP ${response.code}" }
      response.protocol
    }
  }

  companion object {
    /**
     * Two names under `.test`, which RFC 2606 reserves so that they can never be real.
     *
     * They reach the container through this suite's own [Dns] and nothing else, so the reservation
     * is belt and braces — but a fixture name that could one day resolve for real is exactly the
     * kind of thing that turns into a confusing failure years later.
     */
    const val ALPHA = "alpha.coalescing.test"
    const val BETA = "beta.coalescing.test"

    /**
     * One container, carrying a leaf that covers both names.
     *
     * `CERT_HOSTS` is `test-server`'s own mechanism for exactly this: the names are added to the
     * generated leaf's SANs at startup, so one certificate is genuinely valid for both and the
     * client is not being asked to overlook anything.
     */
    @Container
    @JvmStatic
    val server: GenericContainer<*> = TestServer.container().withEnv("CERT_HOSTS", "$ALPHA,$BETA")
  }
}
