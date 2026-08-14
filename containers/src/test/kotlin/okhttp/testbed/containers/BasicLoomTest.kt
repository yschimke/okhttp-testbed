/*
 * Copyright (C) 2024 Square, Inc.
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
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp.testbed.containers.BasicMockServerTest.Companion.MOCKSERVER_IMAGE
import okhttp.testbed.containers.BasicMockServerTest.Companion.trustMockServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.api.parallel.Isolated
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.testcontainers.mockserver.MockServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@Isolated
@EnabledForJreRange(min = JRE.JAVA_21)
class BasicLoomTest {
  // Use mock server so we are strictly testing OkHttp client only in this test.
  // We should test MockWebServer later.
  @Container
  val mockServer: MockServerContainer = MockServerContainer(MOCKSERVER_IMAGE)

  val capturedOut = ByteArrayOutputStream()

  private lateinit var executor: ExecutorService

  private lateinit var client: OkHttpClient

  private val systemOut = System.out

  @BeforeEach
  fun setUp() {
    assertThat(System.getProperty("jdk.tracePinnedThreads")).isNotEmpty()

    client =
      OkHttpClient
        .Builder()
        .trustMockServer()
        .dispatcher(Dispatcher(newVirtualThreadPerTaskExecutor()))
        .build()

    executor = newVirtualThreadPerTaskExecutor()

    // Capture non-deterministic but probable sysout warnings of pinned threads
    // https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html
    System.setOut(PrintStream(capturedOut))
  }

  @AfterEach
  fun checkForPinning() {
    System.setOut(systemOut)
    assertThat(capturedOut.toString()).isEmpty()
  }

  private fun newVirtualThreadPerTaskExecutor(): ExecutorService =
    Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor").invoke(null) as ExecutorService

  @Test
  fun testHttpsRequest() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request()
            .withPath("/person")
            .withQueryStringParameter("name", "peter"),
        ).respond(response().withBody("Peter the person!"))

      val results =
        (1..20).map {
          executor.submit {
            val response =
              client.newCall(Request((mockServer.secureEndpoint + "/person?name=peter").toHttpUrl())).execute()

            val body = response.body.string()
            assertThat(body).contains("Peter the person")
          }
        }

      results.forEach {
        it.get()
      }
    }
  }
}
