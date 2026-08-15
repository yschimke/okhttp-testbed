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
import assertk.assertions.isTrue
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
        .dns(pinned(InetAddress.getByName(BLACKHOLE), reachable))
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
        .dns(pinned(InetAddress.getByName(BLACKHOLE), InetAddress.getByName(BLACKHOLE_TWO)))
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

    assertThat(
      failure is SocketException || failure is SocketTimeoutException,
      name = "failure is a connection failure (${failure.javaClass.simpleName})",
    ).isTrue()

    // Whether the timeout covers the race or each address in turn is the question. Two addresses
    // inside a budget of one timeout says the race is bounded as a whole. The second connection
    // starts after the fast-fallback delay, so leave a small scheduling margin around that one
    // shared budget. What is not acceptable is two timeouts back to back.
    assertThat(elapsed, name = "time to give up on two addresses").isLessThan(CONNECT_TIMEOUT.plus(RACE_MARGIN))
  }

  /**
   * A genuinely dual-stack name connects on a network with only one of the stacks.
   *
   * `ds.test-ipv6.com` publishes both families, and this runner has only v4. The address family
   * that cannot work has to be raced past rather than picked and waited on.
   */
  @Test
  fun aDualStackNameConnectsWithoutIpv6() {
    assumeAvailable(Endpoint.TEST_IPV6)
    assumeTrue(!hasIpv6()) { "this runner has IPv6; the v4-only case cannot be tested here" }

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
   * The other half of the dual-stack check, where IPv6 really is available.
   *
   * Pinning the AAAA answers prevents a working v4 route from making this pass. GitHub-hosted
   * runners skip here because they have no IPv6 route; a dual-stack or v6-only runner must make
   * the request over v6.
   *
   * This uses the dual-stack name, rather than the v6-only name below, so both halves exercise the
   * same DNS identity and differ only in the usable address family.
   */
  @Test
  fun aDualStackNameConnectsUsingIpv6() {
    assumeAvailable(Endpoint.TEST_IPV6)
    assumeTrue(hasIpv6()) { "this runner has no working IPv6 route" }

    val ipv6Addresses = Dns.SYSTEM.lookup(DUAL_STACK).filter { it.address.size == 16 }
    assumeTrue(ipv6Addresses.isNotEmpty()) { "$DUAL_STACK returned no AAAA addresses" }

    OkHttpClient
      .Builder()
      .proxy(Proxy.NO_PROXY)
      .dns(pinned(*ipv6Addresses.toTypedArray()))
      .connectTimeout(CONNECT_TIMEOUT)
      .build()
      .newCall(Request.Builder().url("https://$DUAL_STACK/").build())
      .execute()
      .use { response ->
        assertThat(response.code, name = "$DUAL_STACK over IPv6").isEqualTo(200)
      }
  }

  /**
   * A v6-only name fails promptly on a runner without v6.
   *
   * Depending on where the absence is discovered this is either an `UnknownHostException` or a
   * socket connection failure such as `ConnectException`/`NoRouteToHostException`. It must not
   * consume the full connection timeout: there is no alternative address to wait for.
   */
  @Test
  fun aV6OnlyNameFailsPromptlyWithoutV6() {
    assumeAvailable(Endpoint.TEST_IPV6)
    assumeTrue(!hasIpv6()) { "this runner has working IPv6; the no-v6 failure cannot be tested here" }

    val client =
      OkHttpClient
        .Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    val started = System.nanoTime()
    val failure =
      try {
        client
          .newCall(Request.Builder().url("https://$V6_ONLY/").build())
          .execute()
          .use { throw AssertionError("connected to $V6_ONLY without a working IPv6 route: HTTP ${it.code}") }
      } catch (e: IOException) {
        e
      }
    val elapsed = Duration.ofNanos(System.nanoTime() - started)

    assertThat(
      failure is UnknownHostException || failure is SocketException,
      name = "failure kind (${failure.javaClass.simpleName})",
    ).isTrue()
    assertThat(elapsed, name = "time to reject a v6-only name without v6").isLessThan(CONNECT_TIMEOUT)
  }

  private fun pinned(vararg addresses: InetAddress) =
    object : Dns {
      override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
    }

  private fun addressFor(hostname: String): InetAddress {
    assumeAvailable(Endpoint.GOOGLE)
    val addresses = Dns.SYSTEM.lookup(hostname)
    return addresses.firstOrNull { it.address.size == 4 } ?: addresses.first()
  }

  /** Whether this machine can actually route v6, rather than whether DNS returned an AAAA. */
  private fun hasIpv6(): Boolean =
    try {
      val address = Dns.SYSTEM.lookup(V6_ONLY).firstOrNull { it.address.size == 16 } ?: return false
      Socket().use { socket ->
        socket.connect(InetSocketAddress(address, 443), IPV6_PROBE_TIMEOUT.toMillis().toInt())
      }
      true
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
    val IPV6_PROBE_TIMEOUT: Duration = Duration.ofSeconds(2)
    val RACE_MARGIN: Duration = Duration.ofSeconds(2)
  }
}
