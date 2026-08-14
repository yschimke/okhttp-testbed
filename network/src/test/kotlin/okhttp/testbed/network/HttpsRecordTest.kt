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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import java.net.InetAddress
import java.net.Proxy
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * What OkHttp makes of an `HTTPS` record's parameters.
 *
 * RFC 9460 moved connection setup into DNS: the record can carry the ALPN list, an alternative
 * port, address hints and the ECH configuration, and a client that ignores them makes a worse
 * connection than it needs to. `DohServiceMetadataTest` asks whether the record *arrives*; this
 * asks what it *means* once it has.
 *
 * The interesting parameters are mostly not published by anybody reliable — `port`,
 * `no-default-alpn`, `mandatory` and AliasMode need a fixture, which is issue #19's other half.
 * What the public names do give is the common shape, and two pieces of RFC 9460 semantics that
 * are easy to get wrong and invisible when they are:
 *
 *  - the default ALPN is *implied* when `alpn` is present and `no-default-alpn` is not, so a
 *    record saying `alpn=h3,h2` means three protocols and not two;
 *  - a record with no `alpn` parameter at all is not the same as one listing only the default.
 *
 * One resolver rather than the matrix: the record is the operator's, not the resolver's, and
 * whether resolvers disagree is `DohMatrixTest`'s question rather than this one's.
 *
 * Left out of the build below OkHttp 5.5.0, where `Dns.Record` does not exist.
 */
class HttpsRecordTest {
  /**
   * `alpn=h3,h2` is three protocols.
   *
   * RFC 9460 §7.1.1: the service's default ALPN — `http/1.1` for the `HTTPS` record type — is
   * part of the set unless `no-default-alpn` says otherwise. A client that took the list
   * literally would decide the origin cannot speak HTTP/1.1, which it can, and this is precisely
   * the sort of thing that works everywhere until it meets a server that cares.
   */
  @Test
  fun theDefaultAlpnIsImpliedByTheRecord() {
    val metadata = metadataFor(Endpoint.CLOUDFLARE_WWW.server)

    val alpn = metadata.alpnIds
    assertThat(alpn, name = "${Endpoint.CLOUDFLARE_WWW.server} ALPN ids").isNotNull().isNotEmpty()

    // What the record actually says, checked at the time of writing: `alpn=h3,h2`.
    assertThat(alpn!!, name = "advertised protocols").contains(Protocol.HTTP_2)

    // The implied one. Not in the record, and required to be in the set.
    assertThat(alpn, name = "the default ALPN, implied").contains(Protocol.HTTP_1_1)
  }

  /**
   * A record led by `h3` does not mislead a client that has no HTTP/3.
   *
   * The failure this guards against is a client reading `h3` first and either trying it or giving
   * up. OkHttp has no HTTP/3 (see #12), so the only correct outcome is an ordinary HTTP/2
   * connection — and it has to be HTTP/2 rather than HTTP/1.1, or the ALPN list did nothing.
   */
  @Test
  fun anAlpnListLedByH3StillConnectsOverH2() {
    assumeAvailable(Endpoint.CLOUDFLARE_WWW)

    OkHttpClient()
      .newCall(Request.Builder().url("https://${Endpoint.CLOUDFLARE_WWW.server}/robots.txt").build())
      .execute()
      .use { response ->
        assertThat(response.code, name = "status").isEqualTo(200)
        assertThat(response.protocol, name = "negotiated protocol").isEqualTo(Protocol.HTTP_2)
      }
  }

  /**
   * No `port` parameter means the scheme's port, not "no port".
   *
   * Cloudflare publishes no `port`, so 443 here is OkHttp supplying the default rather than the
   * record carrying it — which is what makes this worth asserting: a build that reported 0 for an
   * absent parameter would send every caller of this API to port 0.
   */
  @Test
  fun anAbsentPortMeansTheSchemeDefault() {
    assertThat(metadataFor(Endpoint.CLOUDFLARE_WWW.server).port, name = "port").isEqualTo(443)
  }

  /**
   * The address hints resolve to somewhere that answers.
   *
   * Hints are hints — a client may ignore them, and OkHttp does connect via the A and AAAA
   * records rather than through these. But a hint pointing nowhere is the failure mode that
   * matters if it ever starts using them, and it costs one request to find out.
   */
  @Test
  fun addressHintsAreReachable() {
    val hints = metadataFor(Endpoint.CLOUDFLARE_WWW.server).ipAddressHints
    assertThat(hints, name = "${Endpoint.CLOUDFLARE_WWW.server} address hints").isNotEmpty()

    // One is enough to answer the question, and four requests to somebody's edge to answer it
    // four times over is not a better test.
    val hint = hints.first()
    val pinned =
      OkHttpClient
        .Builder()
        // Without this the hint is never dialled: a proxy in the way means OkHttp connects to the
        // proxy and the pinned resolver is decoration. The suite would pass having tested nothing.
        .proxy(Proxy.NO_PROXY)
        .dns(
          object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(hint)
          },
        ).build()

    pinned
      .newCall(Request.Builder().url("https://${Endpoint.CLOUDFLARE_WWW.server}/robots.txt").build())
      .execute()
      .use { response ->
        assertThat(response.code, name = "reached ${hint.hostAddress}").isEqualTo(200)
      }
  }

  /**
   * A record with no `alpn` at all leaves the list unset rather than defaulted.
   *
   * `defo.ie` publishes `ipv4hint` and `ech` and no `alpn`, which is the other side of
   * [theDefaultAlpnIsImpliedByTheRecord]: the default is implied *into an existing set*, not
   * conjured where the parameter is absent. Whether a given name publishes `alpn` is its
   * operator's business and can change, which is why this suite reports rather than gates.
   */
  @Test
  fun anAbsentAlpnParameterIsNotAnEmptyList() {
    assertThat(metadataFor(Endpoint.DEFO_IE.server).alpnIds, name = "defo.ie ALPN ids").isEqualTo(null)
  }

  private fun metadataFor(hostname: String): Dns.Record.ServiceMetadata {
    assumeAvailable(Endpoint.GOOGLE_DOH)

    val records =
      DohResolver.GOOGLE
        .builder()
        .includeServiceMetadata(true)
        .build()
        .records(hostname)

    val metadata = records.filterIsInstance<Dns.Record.ServiceMetadata>()
    assertThat(metadata, name = "$hostname HTTPS records").isNotEmpty()
    return metadata.first()
  }

  private fun assumeAvailable(endpoint: Endpoint) {
    val result = Preflight.check(endpoint)
    assumeTrue(result.up) { "${endpoint.server} is unavailable: ${result.detail}" }
  }
}
