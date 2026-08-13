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
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.jupiter.api.Test

/**
 * The smoke test for the network suite: one request over the real internet, asserting the
 * things every later suite here takes for granted — that the runner has outbound network,
 * that TLS completes, and that ALPN negotiated something modern.
 *
 * `generate_204` exists to be fetched by connectivity checks, which makes it about as
 * stable an endpoint as the public internet offers, and it answers with no body at all.
 */
class BasicNetworkTest {
  private val client = OkHttpClient()

  @Test
  fun getOverTls() {
    val response = client.newCall(Request.Builder().url(CONNECTIVITY_CHECK).build()).execute()

    response.use {
      assertThat(response.code).isEqualTo(204)
      assertThat(response.protocol).isIn(Protocol.HTTP_2, Protocol.HTTP_1_1)

      val handshake = response.handshake
      assertThat(handshake).isNotNull()
      assertThat(handshake!!.tlsVersion).isIn(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
    }
  }

  companion object {
    private const val CONNECTIVITY_CHECK = "https://www.google.com/generate_204"
  }
}
