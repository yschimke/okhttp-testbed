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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * HTTP/2 against a stack that shares no code with OkHttp's.
 *
 * MockWebServer speaks HTTP/2 using OkHttp's own framing, its own HPACK encoder and its own flow
 * control. That makes it excellent for testing OkHttp against what OkHttp believes, and useless
 * for testing OkHttp against what nghttp2 believes — a mistake in the shared code is invisible
 * from both sides. These cases use `nghttp2.org`, which is the reference implementation, and
 * which also runs an httpbin at `/httpbin` — that is what makes the framing questions askable
 * without a fixture: a body that needs several `WINDOW_UPDATE`s, a header that needs real HPACK.
 *
 * Connection coalescing is not here. It needs two names on one certificate at one address, and no
 * public pair reliably provides that — `cloudflare.com` and `www.cloudflare.com` share a
 * certificate and sit on different edge addresses, so they cannot coalesce however well the
 * client behaves. `Http2CoalescingTest` makes those conditions against the fixture instead.
 */
class Http2Test {
  @Test
  fun alpnSelectsH2() {
    val response = get("$NGHTTP2/httpbin/get")

    assertThat(response.first, name = "nghttp2.org protocol").isEqualTo(Protocol.HTTP_2)
    // Over TLS, and via ALPN rather than an upgrade: h2c is a different question and has no
    // public endpoint, so it belongs to the fixture half of this issue.
    assertThat(response.second, name = "nghttp2.org handshake").isNotNull()
  }

  /**
   * A body large enough to need flow control arrives whole.
   *
   * 100 KB is comfortably past OkHttp's initial 64 KiB window, so this exercises the path where
   * the client has to *ask* for the rest. A truncated body here would be the same class of
   * failure `HostileResponseTest` guards against, except caused by the client's own accounting
   * rather than by a server misbehaving.
   *
   * 100 KB rather than a megabyte because that is nghttp2's httpbin's ceiling — asking for more
   * returns exactly this much, which would make a larger figure here assert the cap rather than
   * the client.
   */
  @Test
  fun aLargeBodyArrivesIntactUnderFlowControl() {
    assumeAvailable(Endpoint.NGHTTP2)

    val bytes =
      client()
        .newCall(Request.Builder().url("$NGHTTP2/httpbin/bytes/$LARGE_BODY").build())
        .execute()
        .use { response ->
          assertThat(response.code, name = "status").isEqualTo(200)
          response.body.bytes()
        }

    assertThat(bytes.size, name = "bytes received").isEqualTo(LARGE_BODY)
  }

  /**
   * A header far past the HPACK dynamic table, encoded for somebody else's decoder.
   *
   * HPACK is where two implementations most easily agree to differ: the dynamic table is
   * stateful, its size is negotiated, and an encoder that mismanages it produces headers the peer
   * decodes into something else — or fails to decode at all, killing the connection rather than
   * the request. Six kilobytes is past any sensible table size, so it has to be sent literally.
   */
  @Test
  fun aLargeHeaderSurvivesADifferentHpackDecoder() {
    assumeAvailable(Endpoint.NGHTTP2)

    val value = "x".repeat(LARGE_HEADER)
    val echoed =
      client()
        .newCall(
          Request
            .Builder()
            .url("$NGHTTP2/httpbin/headers")
            .header("X-Testbed-Large", value)
            .build(),
        ).execute()
        .use { response ->
          assertThat(response.code, name = "status").isEqualTo(200)
          response.body.string()
        }

    // Echoed back by the server, so this asserts nghttp2 decoded what OkHttp encoded rather than
    // merely that it did not hang up.
    assertThat(echoed.contains(value), name = "the header survived the round trip").isEqualTo(true)
  }

  /**
   * Many requests at once, on one connection, all finishing.
   *
   * OkHttp's `Dispatcher` limit and the server's `SETTINGS_MAX_CONCURRENT_STREAMS` are two
   * different ceilings, and the client has to respect the smaller one without deadlocking or
   * dropping anything. Twenty is comfortably above nghttp2's default of 100 streams being
   * relevant — the point is not to exhaust it but to have more in flight than one.
   */
  @Test
  fun concurrentStreamsAllComplete() {
    assumeAvailable(Endpoint.NGHTTP2)

    val client = client()
    val latch = CountDownLatch(CONCURRENT)
    val codes = ConcurrentHashMap<Int, Int>()
    val failures = ConcurrentHashMap<Int, String>()

    repeat(CONCURRENT) { index ->
      client
        .newCall(Request.Builder().url("$NGHTTP2/httpbin/get?n=$index").build())
        .enqueue(
          object : Callback {
            override fun onResponse(
              call: Call,
              response: Response,
            ) {
              response.use { codes[index] = it.code }
              latch.countDown()
            }

            override fun onFailure(
              call: Call,
              e: java.io.IOException,
            ) {
              failures[index] = e.toString()
              latch.countDown()
            }
          },
        )
    }

    check(latch.await(60, TimeUnit.SECONDS)) { "only ${codes.size + failures.size} of $CONCURRENT finished" }

    assertThat(failures.values.toList(), name = "failures among $CONCURRENT concurrent streams").hasSize(0)
    assertThat(codes.values.filter { it == 200 }, name = "successful streams").hasSize(CONCURRENT)
  }

  private fun client() = OkHttpClient()

  private fun get(url: String): Pair<Protocol, okhttp3.Handshake?> {
    assumeAvailable(Endpoint.NGHTTP2)
    return client().newCall(Request.Builder().url(url).build()).execute().use {
      it.protocol to it.handshake
    }
  }

  private fun assumeAvailable(endpoint: Endpoint) {
    val result = Preflight.check(endpoint)
    assumeTrue(result.up) { "${endpoint.server} is unavailable: ${result.detail}" }
  }

  private companion object {
    const val NGHTTP2 = "https://nghttp2.org"

    /**
     * Past OkHttp's initial flow-control window, and exactly nghttp2's httpbin's ceiling: asking
     * for more returns this, so a larger number would test the server's cap and not the client.
     */
    const val LARGE_BODY = 102400

    /** Past any sensible HPACK dynamic table, so it cannot be indexed away. */
    const val LARGE_HEADER = 6000

    const val CONCURRENT = 20
  }
}
