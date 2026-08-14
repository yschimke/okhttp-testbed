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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The same hostile-response questions, against a server this repository does not operate.
 *
 * `HostileResponseTest` and `HostileRetryTest` in the `containers` suite ask these of a fixture
 * built here. This asks them of testserver.host, which exists for the purpose and is maintained
 * by HTTP Toolkit. Agreement is the expected result; disagreement is a finding about one of the
 * two servers, which is the reason to run both.
 *
 * It is not a duplicate of the local suite, because the failures differ in a way that matters.
 * `/hostile/reset` sends a response head and part of a body before the RST; testserver.host's
 * `/error/reset` and `/error/close` fail with **no response at all**. That is the case where a
 * client cannot know whether the server processed the request, and so the case where retrying is
 * both plausible and dangerous.
 */
@RequiresEndpoint(Endpoint.TESTSERVER_HOST)
class PublicHostileTest {
  private val attempts = AtomicInteger()

  private val client =
    OkHttpClient
      .Builder()
      .addNetworkInterceptor { chain ->
        attempts.incrementAndGet()
        chain.proceed(chain.request())
      }.build()

  /**
   * A connection that dies before a response arrives has to surface as a failure, whether it
   * ends with a FIN or an RST.
   */
  @ParameterizedTest
  @ValueSource(strings = ["/error/close", "/error/reset"])
  fun connectionFailureIsReported(path: String) {
    val failure =
      try {
        client.newCall(Request.Builder().url(url(path)).build()).execute().use { response ->
          response.body.string()
          null
        }
      } catch (e: IOException) {
        e
      }

    assertThat(failure, name = "$path must not read as a response").isNotNull().isInstanceOf(IOException::class)
  }

  /**
   * The public counterpart of `HostileRetryTest.postIsNotRetriedWhenNoResponseArrives`.
   *
   * The local fixture answers this for a server we run; this answers it for one we don't, over a
   * real network where a retry would be a real duplicate request. Both should say one attempt.
   */
  @Test
  fun postIsNotRetriedWhenNoResponseArrives() {
    val request =
      Request
        .Builder()
        .url(url("/error/reset"))
        .post("payload".toRequestBody())
        .build()

    try {
      client.newCall(request).execute().use { it.body.string() }
    } catch (expected: IOException) {
      // Expected: the endpoint exists to kill the connection. The count is what is under test.
    }

    assertThat(attempts.get(), name = "network attempts for one POST with no response").isEqualTo(1)
  }

  /**
   * The control. Without it a run where testserver.host was answering 500 to everything would
   * make the failures above look like successes for this suite.
   */
  @Test
  fun ordinaryRequestSucceeds() {
    client.newCall(Request.Builder().url(url("/status/200")).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }

  private fun url(path: String) = "https://testserver.host$path".toHttpUrl()
}
