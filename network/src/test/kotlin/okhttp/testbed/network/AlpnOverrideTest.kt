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
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.Test

/**
 * Confirms an application can choose the ALPN protocols itself.
 *
 * OkHttp sets ALPN from its own protocol list, so an override only survives if OkHttp is told to
 * leave TLS extensions alone — that is what `supportsTlsExtensions(false)` is doing here. Both
 * halves are asserted: what the socket requested, and what came back. A call that succeeds while
 * OkHttp quietly put its own list back looks identical from the response code alone.
 *
 * The override offers HTTP/1.1, which is not what this client would otherwise settle on with a
 * server that speaks HTTP/2 — so the protocol of the response is the evidence that the override
 * reached the wire. The Android test this came from offers `x-amzn-http-ca`, which nothing here
 * speaks, leaving the outcome to whether the server tolerates an unknown protocol or answers with
 * `no_application_protocol`: that tests the server, not OkHttp.
 *
 * Ported from OkHttp's `android-test`, where it ran as a `Remote` test.
 */
class AlpnOverrideTest {
  class CustomSSLSocketFactory(
    delegate: SSLSocketFactory,
  ) : DelegatingSSLSocketFactory(delegate) {
    override fun configureSocket(sslSocket: SSLSocket): SSLSocket {
      val parameters = sslSocket.sslParameters
      parameters.applicationProtocols = arrayOf(ALPN_PROTOCOL)
      sslSocket.sslParameters = parameters
      return sslSocket
    }
  }

  private var client = OkHttpClient()

  @Test
  fun getWithCustomSocketFactory() {
    val requestedProtocols = mutableListOf<String>()

    client =
      client
        .newBuilder()
        .sslSocketFactory(CustomSSLSocketFactory(client.sslSocketFactory), client.x509TrustManager!!)
        .connectionSpecs(
          listOf(
            ConnectionSpec
              .Builder(ConnectionSpec.MODERN_TLS)
              .supportsTlsExtensions(false)
              .build(),
          ),
        ).eventListener(
          object : EventListener() {
            override fun connectionAcquired(
              call: Call,
              connection: Connection,
            ) {
              val sslSocket = connection.socket() as SSLSocket
              requestedProtocols += sslSocket.sslParameters.applicationProtocols.orEmpty()
              println("Requested " + requestedProtocols.joinToString())
              println("Negotiated " + sslSocket.applicationProtocol)
            }
          },
        ).build()

    val request =
      Request
        .Builder()
        .url("https://www.google.com")
        .build()
    client.newCall(request).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_1_1)
    }

    assertThat(requestedProtocols).containsExactly(ALPN_PROTOCOL)
  }

  companion object {
    /** What the application asks for, in place of the h2-first list OkHttp would send. */
    private const val ALPN_PROTOCOL = "http/1.1"
  }
}
