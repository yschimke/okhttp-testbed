/*
 * Copyright (C) 2020 Square, Inc.
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
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.Test

/**
 * Confirms the two ways of reaching a host under a name other than the one in the URL: overriding
 * the SNI on the socket, and overriding DNS so the URL's name resolves elsewhere.
 *
 * Both end at Cloudflare's `cdn-cgi/trace`, which echoes the host it believes it served — that
 * echo is the assertion, since a connection that silently fell back to the URL's own name would
 * still return 200.
 *
 * Ported from OkHttp's `android-test`, where it ran as a `Remote` test.
 */
class SniOverrideTest {
  private var client =
    OkHttpClient
      .Builder()
      .build()

  @Test
  fun getWithCustomSocketFactory() {
    class CustomSSLSocketFactory(
      delegate: SSLSocketFactory,
    ) : DelegatingSSLSocketFactory(delegate) {
      override fun configureSocket(sslSocket: SSLSocket): SSLSocket {
        val parameters = sslSocket.sslParameters
        println("old SNI: ${parameters.serverNames}")
        parameters.serverNames = mutableListOf<SNIServerName>(SNIHostName(HOST))
        sslSocket.sslParameters = parameters
        return sslSocket
      }
    }

    client =
      client
        .newBuilder()
        .sslSocketFactory(CustomSSLSocketFactory(client.sslSocketFactory), client.x509TrustManager!!)
        .hostnameVerifier { hostname, session ->
          println("hostname: $hostname peerHost: ${session.peerHost}")
          try {
            val cert = session.peerCertificates[0] as X509Certificate
            for (name in cert.subjectAlternativeNames) {
              if (name[0] as Int == 2) {
                println("cert: " + name[1])
              }
            }
            true
          } catch (e: Exception) {
            false
          }
        }.build()

    val request =
      Request
        .Builder()
        .url("https://sni.cloudflaressl.com/cdn-cgi/trace")
        .header("Host", HOST)
        .build()
    client.newCall(request).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)

      assertThat(response.body.string()).contains("h=$HOST")
    }
  }

  @Test
  fun getWithDns() {
    client =
      client
        .newBuilder()
        .dns {
          Dns.SYSTEM.lookup("sni.cloudflaressl.com")
        }.build()

    val request =
      Request
        .Builder()
        .url("https://$HOST/cdn-cgi/trace")
        .build()
    client.newCall(request).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(response.body.string()).contains("h=$HOST")
    }
  }

  companion object {
    private const val HOST = "cloudflare-dns.com"
  }
}
