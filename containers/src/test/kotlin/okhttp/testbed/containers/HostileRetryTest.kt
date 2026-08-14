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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * How many times OkHttp sends a request the server killed under it.
 *
 * `HostileResponseTest` asks whether a malformed response fails. This asks the question issue #10
 * says matters more: **whether it is retried**, and specifically whether a non-idempotent request
 * is retried. A `POST` sent twice because the connection dropped mid-response can charge a card
 * twice; the caller sees one `IOException` either way and cannot tell.
 *
 * This reports rather than gates. Whatever the answer turns out to be, it is a fact about
 * OkHttp's retry policy rather than a defect in this repository, and the recorded result is the
 * point — see "Suites that report rather than gate" in the README.
 *
 * Attempts are counted with a **network** interceptor, which OkHttp invokes once per attempt at
 * reaching the server. An application interceptor would see one call however many times it was
 * sent, which is exactly the blindness this test exists to remove.
 */
@Testcontainers
class HostileRetryTest {
  @Container
  val server: GenericContainer<*> = TestServer.container()

  private val attempts = AtomicInteger()

  private val client =
    OkHttpClient
      .Builder()
      .addNetworkInterceptor { chain ->
        attempts.incrementAndGet()
        chain.proceed(chain.request())
      }.build()

  @Test
  fun postIsNotRetriedAfterAMidResponseReset() {
    sendAndCount("/hostile/reset", post = true)

    assertThat(attempts.get(), name = "network attempts for one POST").isEqualTo(1)
  }

  /**
   * The same reset against a `GET`.
   *
   * Retrying an idempotent request is usually defensible, but not here: `/hostile/reset` sends
   * a status line, headers and part of a body before the RST, so a response head has already
   * been received. Sending the request again at that point would be wrong whatever the method.
   */
  @Test
  fun getIsNotRetriedAfterAMidResponseReset() {
    sendAndCount("/hostile/reset", post = false)

    assertThat(attempts.get(), name = "network attempts for one GET").isEqualTo(1)
  }

  /**
   * The case where a retry is actually plausible, and the one that matters.
   *
   * `/hostile/no-response` accepts the connection, writes nothing at all and closes. No response
   * head ever arrives, so a client cannot know whether the server processed the request — which
   * is exactly the situation OkHttp's `retryOnConnectionFailure` exists for. Retrying an
   * idempotent request here is reasonable; retrying a `POST` is how one payment becomes two.
   *
   * There is deliberately no `GET` counterpart. Retrying a `GET` here would be *correct*, so
   * asserting a count for it would encode a preference as a regression, and a suite that goes
   * amber for good behaviour teaches everyone to ignore the colour.
   */
  @Test
  fun postIsNotRetriedWhenNoResponseArrives() {
    sendAndCount("/hostile/no-response", post = true)

    assertThat(attempts.get(), name = "network attempts for one POST with no response").isEqualTo(1)
  }

  private fun sendAndCount(
    path: String,
    post: Boolean,
  ) {
    val request =
      Request
        .Builder()
        .url(url(path))
        .apply { if (post) post("payload".toRequestBody()) }
        .build()

    try {
      client.newCall(request).execute().use { it.body.string() }
    } catch (expected: IOException) {
      // The failure is the point of the endpoint; how often we got there is what is under test.
    }
  }

  private fun url(path: String) = "http://${server.host}:${server.getMappedPort(TestServer.hostilePort)}$path".toHttpUrl()
}
