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
  fun postIsNotRetriedWhenTheConnectionIsReset() {
    val request =
      Request
        .Builder()
        .url(url("/hostile/reset"))
        .post("payload".toRequestBody())
        .build()

    try {
      client.newCall(request).execute().use { it.body.string() }
    } catch (expected: IOException) {
      // The failure is the point of the endpoint; how often we got there is what is under test.
    }

    assertThat(attempts.get(), name = "network attempts for one POST").isEqualTo(1)
  }

  /**
   * The same reset against a `GET`, recorded for contrast.
   *
   * Retrying an idempotent request is defensible where retrying a `POST` is not, so the two
   * numbers together say more than either alone: if they differ, OkHttp is distinguishing the
   * methods, which is the behaviour you would want.
   */
  @Test
  fun getMayBeRetriedWhenTheConnectionIsReset() {
    try {
      client.newCall(Request.Builder().url(url("/hostile/reset")).build()).execute().use { it.body.string() }
    } catch (expected: IOException) {
      // As above.
    }

    assertThat(attempts.get(), name = "network attempts for one GET").isEqualTo(1)
  }

  private fun url(path: String) = "http://${server.host}:${server.getMappedPort(TestServer.hostilePort)}$path".toHttpUrl()
}
