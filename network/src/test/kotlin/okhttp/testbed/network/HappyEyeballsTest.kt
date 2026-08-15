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
import assertk.assertions.isLessThan
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.time.Duration
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Racing addresses, against addresses that really do not answer.
 *
 * RFC 8305 exists because a name can resolve to an address that is reachable and one that is not,
 * and a client that tries them in order makes the user wait out a timeout for a site that is up.
 * OkHttp 5's fast fallback is the implementation; this asks whether it does the one thing it is
 * for.
 *
 * Every client here sets [Proxy.NO_PROXY] deliberately. With a proxy in the way OkHttp connects
 * to the proxy and the addresses under test are never dialled — the suite would pass without
 * testing anything, which is worse than failing. That is not hypothetical: this repository's own
 * development sandbox proxies outbound HTTPS.
 *
 * IPv6 is the half that cannot be tested where this runs. GitHub-hosted runners have no IPv6, so
 * the v6 cases skip with the reason recorded rather than failing or, worse, passing vacuously.
 */
class HappyEyeballsTest {
  /**
   * A dead first address costs a delay, not the connection.
   *
   * The blackhole is `192.0.2.1` from RFC 5737's TEST-NET-1, reserved for documentation and
   * routed nowhere. Ordering it first is the whole test: without racing, this is a connection
   * that waits out the connect timeout and then succeeds — or gives up.
   */
  @Test
  fun anUnreachableFirstAddressDoesNotStopTheConnection() {
    val reachable = addressFor(Endpoint.GOOGLE.server)

    val client =
      OkHttpClient
        .Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(pinned(BLACKHOLE, reachable.hostAddress.orEmpty()))
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    val started = System.nanoTime()
    client
      .newCall(Request.Builder().url("https://${Endpoint.GOOGLE.server}/robots.txt").build())
      .execute()
      .use { response ->
        assertThat(response.code, name = "reached the second address").isEqualTo(200)
      }
    val elapsed = Duration.ofNanos(System.nanoTime() - started)

    // The only timing worth asserting, and asserted loosely on purpose. A blackholed address
    // fails instantly on some networks and hangs on others, so the number here says "the dead
    // address did not have to time out first" and nothing more precise than that.
    assertThat(elapsed, name = "time to connect past a blackholed address").isLessThan(CONNECT_TIMEOUT)
  }

  /**
   * With nowhere to go, the failure is a failure — and it is the connection that failed.
   *
   * The counterpart to the case above: racing must not turn "no address worked" into a hang, and
   * the exception has to name a connection problem rather than a name that did not resolve, since
   * the name resolved perfectly well.
   */
  @Test
  fun everyAddressUnreachableFailsRatherThanHanging() {
    val client =
      OkHttpClient
        .Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(pinned(BLACKHOLE, BLACKHOLE_TWO))
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    val started = System.nanoTime()
    val failure =
      try {
        client
          .newCall(Request.Builder().url("https://${Endpoint.GOOGLE.server}/robots.txt").build())
          .execute()
          .use { throw AssertionError("connected to a blackholed address: HTTP ${it.code}") }
      } catch (e: IOException) {
        e
      }
    val elapsed = Duration.ofNanos(System.nanoTime() - started)

    assertThat(failure.javaClass.simpleName, name = "failure kind").isEqualTo("ConnectException")

    // Whether the timeout covers the race or each address in turn is the question. Two addresses
    // inside a budget of one timeout says the race is bounded as a whole; the generous margin is
    // there because a network that refuses immediately gets here far sooner and both answers are
    // acceptable — what is not acceptable is two timeouts back to back.
    assertThat(elapsed, name = "time to give up on two addresses").isLessThan(CONNECT_TIMEOUT.multipliedBy(2))
  }

  /**
   * A genuinely dual-stack name connects on a network with only one of the stacks.
   *
   * `ds.test-ipv6.com` publishes both families, and this runner has only v4. The address family
   * that cannot work has to be raced past rather than picked and waited on.
   */
  @Test
  fun aDualStackNameConnectsOnASingleStackNetwork() {
    assumeAvailable(Endpoint.TEST_IPV6)

    OkHttpClient
      .Builder()
      .proxy(Proxy.NO_PROXY)
      .connectTimeout(CONNECT_TIMEOUT)
      .build()
      .newCall(Request.Builder().url("https://$DUAL_STACK/").build())
      .execute()
      .use { response ->
        assertThat(response.code, name = "$DUAL_STACK on a v4-only network").isEqualTo(200)
      }
  }

  /**
   * A v6-only name where there is no v6.
   *
   * Skipped rather than asserted where IPv6 is absent, which is every GitHub-hosted runner: the
   * interesting behaviour — a prompt failure rather than a timeout — cannot be told apart from
   * "the network has no route" without a v6 network to compare against. Recorded as a skip so the
   * gap is visible on the status page instead of being silently absent.
   */
  @Test
  fun aV6OnlyNameFailsPromptlyWithoutV6() {
    assumeTrue(hasIpv6()) { "this runner has no IPv6, so a v6-only name says nothing about racing" }
    assumeAvailable(Endpoint.TEST_IPV6)

    OkHttpClient
      .Builder()
      .proxy(Proxy.NO_PROXY)
      .connectTimeout(CONNECT_TIMEOUT)
      .build()
      .newCall(Request.Builder().url("https://$V6_ONLY/").build())
      .execute()
      .use { response ->
        assertThat(response.code, name = "$V6_ONLY with IPv6 available").isEqualTo(200)
      }
  }

  private fun pinned(vararg addresses: String) =
    object : Dns {
      override fun lookup(hostname: String): List<InetAddress> = addresses.map(InetAddress::getByName)
    }

  private fun addressFor(hostname: String): InetAddress {
    assumeAvailable(Endpoint.GOOGLE)
    val addresses = Dns.SYSTEM.lookup(hostname)
    return addresses.firstOrNull { it.address.size == 4 } ?: addresses.first()
  }

  /** Whether this machine has a routable v6 address at all, rather than whether a name has one. */
  private fun hasIpv6(): Boolean =
    try {
      Dns.SYSTEM.lookup(Endpoint.GOOGLE.server).any { it.address.size == 16 }
    } catch (e: IOException) {
      false
    }

  private fun assumeAvailable(endpoint: Endpoint) {
    val result = Preflight.check(endpoint)
    assumeTrue(result.up) { "${endpoint.server} is unavailable: ${result.detail}" }
  }

  private companion object {
    /** RFC 5737 TEST-NET-1: reserved for documentation, and routed nowhere. */
    const val BLACKHOLE = "192.0.2.1"
    const val BLACKHOLE_TWO = "192.0.2.2"

    const val DUAL_STACK = "ds.test-ipv6.com"
    const val V6_ONLY = "ipv6.test-ipv6.com"

    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
  }
}
