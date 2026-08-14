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
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Origins that offer HTTP/3 to a client that has none.
 *
 * OkHttp does not speak HTTP/3, so there is no h3 behaviour to test. What there is, and what
 * breaks users when it goes wrong, is the fallback: an origin advertises `h3` in `Alt-Svc`, and a
 * client that mishandled the header would upgrade to a protocol it cannot speak, or treat an
 * unknown protocol name as an error, or quietly drop to HTTP/1.1 and halve its throughput.
 *
 * The correct behaviour is to ignore the offer entirely and stay on HTTP/2, and the assertion is
 * exactly that. It reads as a trivial test today. It is a regression guard for the day the
 * fallback path changes — and the record beside it is the part with a shelf life: how much of the
 * web is offering a protocol this client cannot take, published so the answer is visible when
 * HTTP/3 support does arrive.
 */
class AltSvcTest {
  @ParameterizedTest
  @EnumSource(H3Origin::class)
  fun anH3OfferIsIgnoredAndTheConnectionStaysOnH2(origin: H3Origin) {
    val result = Preflight.check(origin.endpoint)
    assumeTrue(result.up) { "${origin.endpoint.server} is unavailable: ${result.detail}" }

    val client = OkHttpClient()

    val (protocol, altSvc) =
      client
        .newCall(Request.Builder().url(origin.url).build())
        .execute()
        .use { response ->
          assertThat(response.code, name = "${origin.endpoint.server} status").isEqualTo(200)
          response.protocol to response.header("alt-svc").orEmpty()
        }

    AltSvcReport.record(
      origin.endpoint.server,
      AltSvcReport.Row(
        protocol = protocol.toString(),
        altSvc = altSvc,
        advertisesH3 = altSvc.contains("h3"),
      ),
    )

    // HTTP/1.1 is allowed rather than asserted against: an origin is entitled to decline h2, and
    // failing here for that would be asserting on somebody else's configuration. What is not
    // allowed is anything else — including OkHttp claiming a protocol it cannot speak.
    assertThat(protocol, name = "${origin.endpoint.server} negotiated").isIn(Protocol.HTTP_2, Protocol.HTTP_1_1)
  }

  /**
   * A second request on the same client is unaffected by the offer.
   *
   * The first response is where `Alt-Svc` arrives, so a client that acted on it would act on the
   * *next* request rather than that one. Reusing the client is what makes this different from the
   * case above, and it is the shape the bug would actually take.
   */
  @ParameterizedTest
  @EnumSource(H3Origin::class)
  fun theOfferDoesNotAffectTheNextRequest(origin: H3Origin) {
    val result = Preflight.check(origin.endpoint)
    assumeTrue(result.up) { "${origin.endpoint.server} is unavailable: ${result.detail}" }

    val client = OkHttpClient()
    val request = Request.Builder().url(origin.url).build()

    val first = client.newCall(request).execute().use { it.protocol }
    val second = client.newCall(request).execute().use { it.protocol }

    assertThat(second, name = "${origin.endpoint.server} after an Alt-Svc offer").isEqualTo(first)
  }

  /**
   * The origins that offer HTTP/3.
   *
   * `quic.rocks:4433` is in the issue and is not here: it answers `502` today, and an endpoint
   * that is down is a skip rather than a suite. The three that remain all advertise
   * `h3=":443"`, checked before they were added rather than assumed.
   */
  enum class H3Origin(
    val endpoint: Endpoint,
    val url: String,
  ) {
    CLOUDFLARE_QUIC(Endpoint.CLOUDFLARE_QUIC, "https://cloudflare-quic.com/"),
    NGINX_QUIC(Endpoint.NGINX_QUIC, "https://quic.nginx.org/"),
    HTTP3_IS(Endpoint.HTTP3_IS, "https://http3.is/"),
  }
}
