/*
 * Copyright (c) 2026 OkHttp Authors
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
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Confirms Encrypted Client Hello (ECH) end to end, against the servers the IETF and DEfO
 * communities run for exactly this.
 *
 * ECH takes two halves. OkHttp supplies the first: an ECH config list read out of the DNS HTTPS
 * record, which is why this uses [DnsOverHttps] with service metadata rather than the system
 * resolver. The TLS stack supplies the second, and that is what varies here: every case runs
 * twice, once on the platform OkHttp picks for itself and once on [EchConscryptPlatform].
 *
 * Parameterising rather than writing two suites is what makes the comparison trustworthy. The
 * request, the resolver, the client and the assertions are one piece of code, so a difference
 * between the two results cannot be a difference between two copies of a test that drifted — it
 * is the platform, and the platforms differ by one call to `Conscrypt.setEchConfigList`.
 *
 * On [TlsPlatform.JDK] the route assertions pass and the assertions about what the server saw do
 * not, and that is the finding this suite exists to report: it cannot change until a Conscrypt
 * carrying the ECH API is released and OkHttp can compile against it. Those failures are marked
 * expected on the status page. A failure on [TlsPlatform.CONSCRYPT_ECH] is not expected, and is
 * the one worth waking up for.
 *
 * Ported from OkHttp's `android-test`, where it runs as a `Remote` test on API 37 and above. Two
 * of the cases there don't cross over: the `AndroidDns` variant, and the one covering a host
 * excluded by `network_security_config.xml` — both are Android platform behaviour, not OkHttp's.
 */
@RequiresEndpoint(Endpoint.CLOUDFLARE_DOH)
class EchTest {
  /** The TLS stack under test. */
  enum class TlsPlatform {
    /** Whatever OkHttp chooses unaided, which on a JVM is `Jdk9Platform`. */
    JDK,

    /** OkHttp's platform with the one call `ConscryptPlatform` omits. */
    CONSCRYPT_ECH,
  }

  private lateinit var client: OkHttpClient

  /**
   * Installs [platform] and builds a client on it.
   *
   * Called from the test rather than from a `@BeforeEach`, because which platform to install is
   * the test's parameter and a `@BeforeEach` can't see it. The client has to be built afterwards
   * either way: OkHttp reads the platform when it builds a socket factory, so one built earlier
   * would quietly keep the old stack and the suite would compare a platform against itself.
   */
  private fun useClientOn(platform: TlsPlatform) {
    when (platform) {
      TlsPlatform.JDK -> EchConscryptPlatform.uninstall()
      TlsPlatform.CONSCRYPT_ECH -> {
        assumeTrue(ConscryptEch.isSupported) {
          "requires a Conscrypt with ECH. Run conscrypt/fetch-conscrypt.sh."
        }
        EchConscryptPlatform.install()
      }
    }

    val bootstrapClient = OkHttpClient()

    // DNS server is addressed by IP, so resolving the resolver doesn't need a resolver.
    val dns =
      DnsOverHttps
        .Builder()
        .client(bootstrapClient)
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .includeServiceMetadata(true)
        .build()

    client =
      bootstrapClient
        .newBuilder()
        .addNetworkInterceptor(RouteTagger)
        .dns(dns)
        .build()
  }

  @AfterEach
  fun tearDown() {
    // The platform is process-wide, so leaving it installed would hand it to the next suite.
    EchConscryptPlatform.uninstall()
  }

  @ParameterizedTest(name = "{displayName} {0}")
  @EnumSource(TlsPlatform::class)
  @RequiresEndpoint(Endpoint.CLOUDFLARE_ECH)
  fun cloudflareUsesEch(platform: TlsPlatform) {
    useClientOn(platform)

    val call = client.newCall(Request("https://cloudflare-ech.com/cdn-cgi/trace".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("sni=encrypted")
    }
  }

  @ParameterizedTest(name = "{displayName} {0}")
  @EnumSource(TlsPlatform::class)
  @RequiresEndpoint(Endpoint.TLS_ECH_DEV)
  fun echIsAcceptedOnTlsEchDev(platform: TlsPlatform) {
    useClientOn(platform)

    val call = client.newCall(Request("https://tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()

      // Only the heading identifies the server we reached; every page links to all of the others.
      assertThat(body).contains("<h1>tls-ech.dev</h1>")
      assertThat(body).contains("You are using ECH")
      assertThat(body).doesNotContain("not using ECH")
    }
  }

  @ParameterizedTest(name = "{displayName} {0}")
  @EnumSource(TlsPlatform::class)
  @RequiresEndpoint(Endpoint.TLS_ECH_DEV)
  fun echIsRetriedOnStaleTlsEchDev(platform: TlsPlatform) {
    useClientOn(platform)

    val call = client.newCall(Request("https://stale.tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      val routes = call.routeList.routes
      assertThat(routes).hasSize(2)
      assertThat(routes[0].echConfigList).isNotNull()
      assertThat(routes[1].echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("<h1>stale.tls-ech.dev</h1>")
      assertThat(body).contains("You are using ECH")
      assertThat(body).doesNotContain("not using ECH")
    }
  }

  /**
   * This page redirects to 'https://wrong.tls-ech.dev:445/', but nothing is listening on that port
   * on that server.
   */
  @ParameterizedTest(name = "{displayName} {0}")
  @EnumSource(TlsPlatform::class)
  @RequiresEndpoint(Endpoint.TLS_ECH_DEV)
  fun echIsAcceptedOnWrongTlsEchDev(platform: TlsPlatform) {
    useClientOn(platform)

    val verifiedHostnames = mutableListOf<String>()
    val hostnameVerifier = client.hostnameVerifier
    val client =
      client
        .newBuilder()
        .hostnameVerifier { hostname, session ->
          verifiedHostnames += hostname
          hostnameVerifier.verify(hostname, session)
        }.followRedirects(false)
        .build()

    val call = client.newCall(Request("https://wrong.tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      assertThat(response.code).isEqualTo(302)
      assertThat(response.headers["Location"])
        .isEqualTo("https://wrong.tls-ech.dev:445/")
      assertThat(verifiedHostnames).contains("wrong.tls-ech.dev")
    }
  }

  /** TLS 1.2 cannot carry ECH. */
  @ParameterizedTest(name = "{displayName} {0}")
  @EnumSource(TlsPlatform::class)
  @RequiresEndpoint(Endpoint.TLS_ECH_DEV)
  fun tlsIsNotUsedOnTls12TlsEchDev(platform: TlsPlatform) {
    useClientOn(platform)

    val call = client.newCall(Request("https://tls12.tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      val routes = call.routeList.routes
      assertThat(routes).hasSize(2)
      assertThat(routes[0].echConfigList).isNotNull()
      assertThat(routes[1].echConfigList).isNull()

      val body = response.body.string()
      assertThat(body).contains("<h1>tls12.tls-ech.dev</h1>")
      assertThat(body).contains("You are not using ECH")
      assertThat(body).doesNotContain("You are using ECH")
    }
  }

  @ParameterizedTest(name = "{displayName} {0}")
  @EnumSource(TlsPlatform::class)
  @RequiresEndpoint(Endpoint.DEFO_IE)
  fun echIsAcceptedOnDefoIe(platform: TlsPlatform) {
    useClientOn(platform)

    val call = client.newCall(Request("https://defo.ie/ech-check.php".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("SSL_ECH_STATUS: success")
    }
  }
}

/**
 * Collect Route information to confirm we sent an ECH config list to our TLS stack. Whether we
 * actually encrypted the client hello depends on our TLS stack.
 */
internal object RouteTagger : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val routeList = chain.call().routeList
    routeList.routes += chain.connection()!!.route()
    return chain.proceed(chain.request())
  }
}

internal val Call.routeList: RouteList
  get() = tag(RouteList::class) { RouteList() }

/**
 * All of the routes used to retrieve an HTTP response.
 */
internal class RouteList {
  val routes = mutableListOf<Route>()
}
