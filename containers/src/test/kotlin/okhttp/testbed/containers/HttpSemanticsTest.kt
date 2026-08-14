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
package okhttp.testbed.containers

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.time.Duration
import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The HTTP semantics that bite, against a fixture that answers exactly.
 *
 * `GoHttpbinTest` runs the same family of questions against `go-httpbin`, which is the value of
 * having both: two independent servers agreeing is evidence, and one of them disagreeing is the
 * interesting result. This one uses `test-server`, so the awkward cases — a redirect that must
 * change the method, a cross-host hop that must drop a credential — can be arranged precisely
 * rather than hoped for.
 *
 * It gates. Nothing third-party is involved and every answer here is OkHttp's own.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpSemanticsTest {
  /**
   * 301, 302 and 303 turn a POST into a GET; 307 and 308 do not.
   *
   * This is the oldest wart in HTTP and the one most likely to surprise: the first three were
   * specified as "do not change the method" and implemented as "change it", and 307/308 exist
   * because that battle was lost. A client that got it backwards would silently turn a payment
   * into a page view, or replay a body nobody expected.
   */
  @ParameterizedTest
  @ValueSource(ints = [301, 302, 303])
  fun theseRedirectsRewriteTheMethodToGet(status: Int) {
    val body = echoAfterRedirect(status)

    assertThat(body, name = "method after a $status").contains("\"method\": \"GET\"")
  }

  @ParameterizedTest
  @ValueSource(ints = [307, 308])
  fun theseRedirectsKeepTheMethodAndTheBody(status: Int) {
    val body = echoAfterRedirect(status)

    assertThat(body, name = "method after a $status").contains("\"method\": \"POST\"")
    // The body has to be sent again, not dropped: a preserved method with a lost body is the
    // worst of both, and it is a mistake a client can make in exactly one place.
    assertThat(body, name = "body after a $status").contains(REDIRECT_BODY)
  }

  /**
   * A credential does not follow a redirect to another host.
   *
   * The failure this prevents is sending an `Authorization` header to whoever the first server
   * cared to name, which is a credential leak triggered remotely. Both halves are asserted, since
   * "never forwards it" would be just as wrong in the other direction: on the *same* host the
   * header has to survive, or every authenticated redirect breaks.
   */
  @Test
  fun authorizationIsDroppedOnACrossHostRedirectAndKeptOnASameHostOne() {
    val across =
      echo(
        Request
          .Builder()
          .url(url("/status/302?location=http://$OTHER_NAME:${server.getMappedPort(TestServer.PLAIN_PORT)}/anything"))
          .header("Authorization", CREDENTIAL)
          .build(),
      )

    assertThat(across, name = "the second host's view of the request").doesNotContain(CREDENTIAL)

    val within =
      echo(
        Request
          .Builder()
          .url(url("/status/302?location=/anything"))
          .header("Authorization", CREDENTIAL)
          .build(),
      )

    assertThat(within, name = "a same-host redirect").contains(CREDENTIAL)
  }

  /**
   * gzip is undone on the way in; anything else is handed over as it arrived.
   *
   * OkHttp offers `Accept-Encoding: gzip` itself and decodes what comes back, which is why the
   * `/gzip` body reads as JSON and carries no `Content-Length` — the length described the
   * compressed bytes and would be a lie about the ones the caller sees.
   *
   * `deflate` is the other half and the one worth writing down: the fixture sends it whether or
   * not it was asked for, and OkHttp passes it straight through. A caller who assumes "compressed
   * responses are handled" gets bytes rather than text, and it is their job to decode them.
   */
  @Test
  fun gzipIsTransparentAndDeflateIsNot() {
    client().newCall(Request.Builder().url(url("/gzip")).build()).execute().use { response ->
      assertThat(response.body.string(), name = "a gzip body").contains("\"encoding\":\"gzip\"")
      assertThat(response.header("Content-Length"), name = "length of a decoded body").isEqualTo(null)
    }

    client().newCall(Request.Builder().url(url("/deflate")).build()).execute().use { response ->
      assertThat(response.header("Content-Encoding"), name = "deflate is left in place").isEqualTo("deflate")
      // Still deflate bytes, so the JSON the fixture compressed is not readable as text.
      assertThat(response.body.string(), name = "a deflate body").doesNotContain("\"encoding\"")
    }
  }

  /**
   * A redirect loop ends, and says so.
   *
   * Following forever is the failure that looks like a hang. The limit is OkHttp's own and the
   * exception is the only thing that tells a caller what happened.
   */
  @Test
  fun aLongRedirectChainIsAbandoned() {
    val failure =
      try {
        client().newCall(Request.Builder().url(url("/redirect/30")).build()).execute().use {
          throw AssertionError("followed thirty redirects: HTTP ${it.code}")
        }
      } catch (e: IOException) {
        e
      }

    assertThat(failure.message.orEmpty(), name = "a chain past the limit").contains("follow-up")
  }

  /** 204 and 304 carry no body, and a client that expected one would hang waiting for it. */
  @ParameterizedTest
  @ValueSource(ints = [204, 304])
  fun aBodilessStatusHasNoBody(status: Int) {
    client().newCall(Request.Builder().url(url("/status/$status")).build()).execute().use { response ->
      assertThat(response.code, name = "status").isEqualTo(status)
      assertThat(response.body.string(), name = "body of a $status").isEqualTo("")
    }
  }

  /**
   * A cookie set by the server comes back on the next request — through the jar, and only there.
   *
   * OkHttp has no cookie store unless one is supplied, so the default client sending nothing back
   * is correct rather than broken. This asserts the wiring: given a jar, the round trip happens.
   */
  @Test
  fun cookiesRoundTripThroughTheJar() {
    val jar = RecordingCookieJar()
    val client = client().newBuilder().cookieJar(jar).build()

    client.newCall(Request.Builder().url(url("/cookies/set?flavour=ginger")).build()).execute().close()

    assertThat(jar.stored.size, name = "cookies the server set").isGreaterThan(0)

    val echoed =
      client.newCall(Request.Builder().url(url("/cookies")).build()).execute().use { it.body.string() }

    assertThat(echoed, name = "the cookie coming back").contains("ginger")
  }

  /**
   * A read timeout and a call timeout are different failures, and have to look different.
   *
   * They are the two a caller configures for different reasons — one bounds a single quiet
   * stretch, the other bounds the whole operation — and a library that reported them
   * identically would make either impossible to diagnose.
   */
  @Test
  fun readAndCallTimeoutsAreDistinguishable() {
    val readTimeout =
      timeoutFailure(client().newBuilder().readTimeout(Duration.ofSeconds(1)).build())
    val callTimeout =
      timeoutFailure(client().newBuilder().callTimeout(Duration.ofSeconds(1)).build())

    assertThat(readTimeout, name = "a read timeout").isInstanceOf(SocketTimeoutException::class)
    assertThat(callTimeout, name = "a call timeout").isInstanceOf(InterruptedIOException::class)

    // The half that does the distinguishing. `SocketTimeoutException` extends
    // `InterruptedIOException`, so asserting the call timeout is the latter is satisfied by the
    // former too — the two would be indistinguishable and this test would say nothing. What
    // separates them is that a call timeout is *not* a socket timeout.
    assertThat(callTimeout is SocketTimeoutException, name = "a call timeout is not a read timeout")
      .isEqualTo(false)
    assertThat(callTimeout.message.orEmpty(), name = "what a call timeout says").contains("timeout")
  }

  private fun timeoutFailure(client: OkHttpClient): IOException =
    try {
      client.newCall(Request.Builder().url(url("/delay/5")).build()).execute().use {
        throw AssertionError("a five-second delay answered inside a one-second timeout")
      }
    } catch (e: IOException) {
      e
    }

  private fun echoAfterRedirect(status: Int): String =
    echo(
      Request
        .Builder()
        .url(url("/status/$status?location=/anything"))
        .post(REDIRECT_BODY.toRequestBody())
        .build(),
    )

  private fun echo(request: Request): String =
    client().newCall(request).execute().use { response ->
      assertThat(response.code, name = "the request reached /anything").isEqualTo(200)
      response.body.string()
    }

  /**
   * Every name used here resolves to the container.
   *
   * [OTHER_NAME] is what makes the cross-host redirect genuinely cross-host: a second name for
   * one server, so the hop changes the *host* and nothing else. Under `.test`, which RFC 2606
   * reserves, so it can never become a real name.
   */
  private fun client() =
    OkHttpClient
      .Builder()
      .dns(
        object : Dns {
          override fun lookup(hostname: String): List<InetAddress> =
            when (hostname) {
              OTHER_NAME -> listOf(InetAddress.getByName(server.host))
              else -> Dns.SYSTEM.lookup(hostname)
            }
        },
      ).build()

  private fun url(path: String) = "http://${server.host}:${server.getMappedPort(TestServer.PLAIN_PORT)}$path".toHttpUrl()

  /** Just enough of a jar to see the round trip happen. */
  private class RecordingCookieJar : okhttp3.CookieJar {
    val stored = mutableListOf<Cookie>()

    override fun saveFromResponse(
      url: HttpUrl,
      cookies: List<Cookie>,
    ) {
      stored += cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = stored.filter { it.matches(url) }
  }

  companion object {
    const val REDIRECT_BODY = "testbed-redirect-body"
    const val CREDENTIAL = "Bearer testbed-credential"

    /** A second name for the same container, so a redirect can cross hosts without leaving it. */
    const val OTHER_NAME = "elsewhere.semantics.test"

    @Container
    @JvmStatic
    val server: GenericContainer<*> = TestServer.container()
  }
}
