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
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Five real resolvers, and whether they agree.
 *
 * `okhttp-dnsoverhttps` is tested upstream against recorded responses. What that cannot cover is
 * live resolvers disagreeing — and disagreement is not a bug: Quad9 and AdGuard filter, so a name
 * that answers at Cloudflare and not at Quad9 is a filtering result. Recording the difference is
 * the point of the matrix.
 *
 * Every case skips rather than fails when its resolver is unavailable. Public resolvers rate-limit
 * and rightly so; a throttled resolver is an outage, not a finding about OkHttp. The preflight is
 * consulted per resolver rather than per class, because a parameterised case cannot declare its
 * own `@RequiresEndpoint` — so [DohResolver] carries the endpoint and each case asks about its own.
 *
 * The whole suite queries a fixed handful of names, once per scheduled run.
 */
class DohResolverTest {
  @ParameterizedTest
  @EnumSource(DohResolver::class)
  fun resolvesADualStackName(resolver: DohResolver) {
    val addresses = resolver.available().lookup(DUAL_STACK)

    assertThat(addresses, name = "${resolver.id} on $DUAL_STACK").isNotEmpty()

    // Both families, because includeIPv6 is on by default and a resolver that quietly dropped
    // AAAA would still look healthy on a v4-only assertion.
    assertThat(
      addresses.firstOrNull { it is Inet4Address },
      name = "${resolver.id} IPv4 for $DUAL_STACK",
    ).isNotNull()
    assertThat(
      addresses.firstOrNull { it is Inet6Address },
      name = "${resolver.id} IPv6 for $DUAL_STACK",
    ).isNotNull()
  }

  /**
   * A name that does not exist has to arrive as a failure, not as an empty success.
   *
   * An empty list returned as success is the dangerous shape: callers that check `isEmpty()` are
   * rare, so it surfaces later as a connection to nowhere rather than as a resolution error.
   */
  @ParameterizedTest
  @EnumSource(DohResolver::class)
  fun unknownNameThrows(resolver: DohResolver) {
    val dns = resolver.available()

    val failure =
      try {
        val addresses = dns.lookup(NXDOMAIN)
        throw AssertionError("${resolver.id} returned $addresses for $NXDOMAIN instead of failing")
      } catch (e: UnknownHostException) {
        e
      }

    assertThat(failure, name = "${resolver.id} on $NXDOMAIN").isNotNull()
  }

  /**
   * RFC 8484 allows both, and they are different code paths: `POST` carries the query as a body,
   * `GET` as base64url in the path with the padding stripped. Some resolvers reject padded
   * base64url and some tolerate it, so a client that got the encoding wrong would work against
   * half the matrix — which is exactly the kind of thing one resolver cannot tell you.
   */
  @ParameterizedTest
  @EnumSource(DohResolver::class)
  fun getAndPostAgree(resolver: DohResolver) {
    val viaPost = resolver.available(post = true).lookup(DUAL_STACK)
    val viaGet = resolver.available(post = false).lookup(DUAL_STACK)

    assertThat(viaPost, name = "${resolver.id} POST").isNotEmpty()
    assertThat(viaGet, name = "${resolver.id} GET").isNotEmpty()
  }

  /**
   * The bootstrap question: resolving `dns.google` needs DNS, and the DoH client is the DNS.
   *
   * OkHttp's answer is `bootstrapDnsHosts`, and this checks the loop is actually broken rather
   * than merely unlikely — a recursion here would hang rather than fail, which is why the suite
   * is worth having even though the happy path looks obvious.
   */
  @Test
  fun bootstrappingByNameDoesNotRecurse() {
    val resolver = DohResolver.GOOGLE
    assumeAvailable(resolver)

    val bootstrap =
      DnsOverHttps
        .Builder()
        .client(OkHttpClient())
        .url(resolver.url.toHttpUrl())
        .bootstrapDnsHosts(resolver.bootstrapAddresses())
        .build()

    assertThat(bootstrap.lookup(DUAL_STACK), name = "resolved through a name-addressed resolver").isNotEmpty()
  }

  private fun DohResolver.available(post: Boolean = true): DnsOverHttps {
    assumeAvailable(this)
    return dns(post = post)
  }

  private fun assumeAvailable(resolver: DohResolver) {
    val result = Preflight.check(resolver.endpoint)
    assumeTrue(result.up) { "${resolver.endpoint.server} is unavailable: ${result.detail}" }
  }

  companion object {
    /** Stable, dual-stack, and answered by every resolver in the matrix. */
    const val DUAL_STACK = "www.google.com"

    /** `.invalid` is reserved by RFC 2606 precisely so that it never resolves. */
    const val NXDOMAIN = "no-such-name.invalid"
  }
}
