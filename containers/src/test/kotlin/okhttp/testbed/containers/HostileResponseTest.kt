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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Responses that are wrong on purpose, and what OkHttp does with them.
 *
 * httpbin covers what a well-behaved server does; the failures that reach users come from the
 * other kind. `test-server` produces these by hijacking the connection and writing bytes no HTTP
 * library would emit — a reset mid-body, a body shorter than its `Content-Length`, chunks with no
 * terminator, a status line that is not one.
 *
 * What this suite asserts is deliberately narrow: **the call fails rather than returning a body
 * as if nothing were wrong**. Silently accepting a truncated body is the failure mode that
 * actually costs users data, and it is invisible against a well-behaved server.
 *
 * What it does *not* assert is which exception, beyond `IOException`. Which one a client reports
 * for malformed framing is the client's business, and pinning it would turn a change in OkHttp's
 * error reporting into a failure about response parsing — the same reasoning `BadChainTest`
 * applies to TLS.
 *
 * Whether a failure is *retried* is a separate question and lives in `HostileRetryTest`, because
 * the answer is a finding about OkHttp rather than something this repository can call broken.
 */
@Testcontainers
class HostileResponseTest {
  @Container
  val server: GenericContainer<*> = TestServer.container()

  private val client = OkHttpClient()

  /**
   * Every one of these is malformed in a way no client can reasonably accept, so a failure is an
   * invariant rather than a fact about this version.
   *
   * Deliberately not here: `duplicate-content-length`, `content-length-and-chunked`,
   * `huge-header` and `informational-storm`. Those are *ambiguous* rather than broken — the RFCs
   * give a client latitude, and asserting either outcome would be asserting a preference. They
   * belong with the recorded behaviour in `HostileRetryTest`'s companion issue, not here.
   */
  @ParameterizedTest
  @ValueSource(
    strings = [
      "/hostile/no-response",
      "/hostile/reset",
      "/hostile/truncated-body",
      "/hostile/truncated-chunks",
      "/hostile/invalid-chunk-size",
      "/hostile/invalid-status-line",
    ],
  )
  fun malformedResponseFails(path: String) {
    val failure =
      try {
        client.newCall(Request.Builder().url(url(path)).build()).execute().use { response ->
          // The body has to be read: a truncated body is a complete set of headers followed by
          // a short read, so a test that only looked at the status line would pass while the
          // caller lost data.
          response.body.string()
          null
        }
      } catch (e: IOException) {
        e
      }

    assertThat(failure, name = "$path must not read as a complete response")
      .isNotNull()
      .isInstanceOf(IOException::class)
  }

  /**
   * The positive control, and the one case here that is unusual rather than broken.
   *
   * `half-close` writes a complete, well-formed response and only then shuts its write half. A
   * client that treats that as an error is too strict, and — more to the point for this suite —
   * if this failed too, the assertions above would be proving nothing more than "the container
   * is unreachable".
   */
  @Test
  fun completeResponseBeforeHalfCloseIsAccepted() {
    client.newCall(Request.Builder().url(url("/hostile/half-close")).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      response.body.string()
    }
  }

  private fun url(path: String) = "http://${server.host}:${server.getMappedPort(TestServer.hostilePort)}$path".toHttpUrl()
}
