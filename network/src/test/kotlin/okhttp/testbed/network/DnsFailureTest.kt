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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * How a resolution failure reaches the caller.
 *
 * A name that does not resolve is the ordinary case; what matters is the *shape* of the failure.
 * The dangerous one is an empty address list returned as a success, because almost nobody checks
 * for it and it surfaces much later as a connection to nowhere. The next most dangerous is a
 * failure that all looks alike, so a caller cannot tell "this name does not exist" from "the
 * resolver is having a bad day" and retries — or doesn't — for the wrong reason.
 *
 * The names are chosen so that the *server* produces the failure rather than the test faking it:
 * `dnssec-failed.org` is a deliberately broken signature published for this purpose, and a random
 * label under a real zone is an NXDOMAIN nobody has to maintain.
 *
 * Which resolvers actually validate is a separate question, and a per-resolver one, so it is
 * asked in [DohMatrixTest] where the answers can be published side by side.
 */
class DnsFailureTest {
  /**
   * A validating resolver's SERVFAIL arrives as `UnknownHostException`, with something to say.
   *
   * `dnssec-failed.org` is signed with a key that does not match, so a resolver that validates
   * refuses to answer. That is not the same as the name not existing, and the next case is the
   * other half of that pair.
   */
  @Test
  fun aValidationFailureIsAnUnknownHost() {
    val failure = lookupFailure(SERVFAIL)

    assertThat(failure, name = "$SERVFAIL").isInstanceOf(UnknownHostException::class)

    // The message today is "DNS server failure". Asserted as *present* rather than as that exact
    // string: the wording is OkHttp's to change, but a caller with nothing at all to log is the
    // regression worth catching.
    assertThat(failure.message, name = "$SERVFAIL detail").isNotNull()
  }

  /**
   * NXDOMAIN and SERVFAIL do not read alike.
   *
   * The two are different questions — "there is no such name" against "I could not find out" —
   * and only the second is worth retrying. Today they are distinguishable in the crudest possible
   * way: SERVFAIL carries a message and NXDOMAIN's is `null`. That is a low bar, and asserting
   * that they *differ* rather than pinning either wording keeps this passing if OkHttp raises it.
   */
  @Test
  fun nxdomainDoesNotLookLikeAServerFailure() {
    val nxdomain = lookupFailure(NXDOMAIN)
    val servfail = lookupFailure(SERVFAIL)

    assertThat(nxdomain, name = NXDOMAIN).isInstanceOf(UnknownHostException::class)
    assertThat(nxdomain.message, name = "NXDOMAIN against SERVFAIL").isNotEqualTo(servfail.message)
  }

  /**
   * A resolver that answers with an HTTP error is not a name that failed to resolve.
   *
   * This is the finding worth having from this suite. A rate-limited or broken DoH endpoint
   * raises a plain `IOException` — `response: 429 Too Many Requests` — and **not** an
   * `UnknownHostException`, so a caller catching the latter to mean "bad name" will not catch it,
   * and one catching it to decide whether to retry will make the opposite decision from the
   * correct one. Recorded here rather than argued about: the assertion is only that the two are
   * not conflated.
   */
  @Test
  fun aResolverHttpErrorIsNotAnUnknownHost() {
    assumeAvailable(Endpoint.TESTSERVER_HOST)

    // A DoH URL that answers every query with 429. Nothing about the *name* being looked up is
    // wrong, which is the point.
    val throttled =
      DnsOverHttps
        .Builder()
        .client(OkHttpClient())
        .url("https://${Endpoint.TESTSERVER_HOST.server}/status/429".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getAllByName(Endpoint.TESTSERVER_HOST.server).toList())
        .build()

    val failure =
      try {
        val addresses = throttled.lookup(DohResolverTest.DUAL_STACK)
        throw AssertionError("a throttled resolver returned $addresses instead of failing")
      } catch (e: IOException) {
        e
      }

    assertThat(failure, name = "a 429 from the resolver").isInstanceOf(IOException::class)
    if (failure is UnknownHostException) {
      throw AssertionError("a 429 from the resolver arrived as UnknownHostException: ${failure.message}")
    }
  }

  /**
   * No addresses of one family is not "no addresses".
   *
   * `ipv4only.arpa` exists precisely to have A records and no AAAA. With `includeIPv6` on by
   * default, the AAAA query comes back NOERROR with nothing in it, and a client that treated an
   * empty answer to *either* query as failure would make this name unresolvable — on a v4-only
   * network, which is most CI.
   */
  @Test
  fun noDataForOneFamilyStillResolvesTheOther() {
    assumeAvailable(Endpoint.GOOGLE_DOH)

    val addresses = DohResolver.GOOGLE.dns().lookup(V4_ONLY)

    assertThat(addresses, name = "$V4_ONLY addresses").isNotEmpty()
  }

  private fun lookupFailure(hostname: String): Exception {
    assumeAvailable(Endpoint.GOOGLE_DOH)

    return try {
      val addresses = DohResolver.GOOGLE.dns().lookup(hostname)
      throw AssertionError("$hostname resolved to $addresses instead of failing")
    } catch (e: IOException) {
      e
    }
  }

  private fun assumeAvailable(endpoint: Endpoint) {
    val result = Preflight.check(endpoint)
    assumeTrue(result.up) { "${endpoint.server} is unavailable: ${result.detail}" }
  }

  private companion object {
    /** Signed with a key that does not match, on purpose, since 2009. */
    const val SERVFAIL = "www.dnssec-failed.org"

    /** A label nobody has registered under a zone that certainly exists. */
    const val NXDOMAIN = "no-such-label-9f3a.google.com"

    /** RFC 8880: A records and deliberately no AAAA. */
    const val V4_ONLY = "ipv4only.arpa"
  }
}
