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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * The self-hosted half of the httpbin coverage.
 *
 * httpbin.org rate-limits and httpbingo.org is somebody else's server, so the HTTP semantics this
 * repository leans on run against a container instead: pinned, deterministic, and answering the
 * same way next month as today. The public endpoints stay in the `network` suite, where the
 * interesting result is the two disagreeing.
 *
 * This suite proves the fixture works. The semantics matrix it exists to carry is its own issue.
 */
@Testcontainers
class GoHttpbinTest {
  @Container
  val httpbin: GenericContainer<*> = goHttpbin()

  private val client = OkHttpClient()

  private fun url(path: String) = "http://${httpbin.host}:${httpbin.firstMappedPort}$path".toHttpUrl()

  @Test
  fun testStatusCode() {
    client.newCall(Request.Builder().url(url("/status/418")).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(418)
    }
  }

  @Test
  fun testGzipIsDecompressedTransparently() {
    client.newCall(Request.Builder().url(url("/gzip")).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      // The flag, not its formatting: whether the JSON is pretty-printed is go-httpbin's business
      // and not what this asserts. Reading it at all is the point — it arrived compressed.
      assertThat(response.body.string()).contains("gzipped")

      // OkHttp added the Accept-Encoding itself, so it owns the decompression and strips the
      // header describing it. A Content-Encoding surviving here would mean the body didn't.
      assertThat(response.header("Content-Encoding")).isNull()
    }
  }

  @Test
  fun testRedirectsAreFollowed() {
    client.newCall(Request.Builder().url(url("/redirect/2")).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.request.url.encodedPath).isEqualTo("/get")

      // Two hops, so two prior responses, each a 302.
      val redirects = generateSequence(response.priorResponse) { it.priorResponse }.toList()
      assertThat(redirects).hasSize(2)
      assertThat(redirects.map { it.code }.toSet()).isEqualTo(setOf(302))
    }
  }

  @Test
  fun testStreamedResponseIsChunked() {
    client.newCall(Request.Builder().url(url("/stream/3")).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(200)

      // /stream flushes per line and so has no Content-Length. /drip is the opposite — it sets
      // one deliberately — which is why the slow-body tests will want that one instead.
      assertThat(response.header("Content-Length")).isNull()
      assertThat(response.body.string().trim().lines()).hasSize(3)
    }
  }

  @Test
  fun testUnauthenticatedRequestIsChallenged() {
    client.newCall(Request.Builder().url(url("/basic-auth/user/passwd")).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(401)
      assertThat(response.header("WWW-Authenticate")).isNotNull().contains("Basic")
    }
  }

  companion object {
    // Pinned inline rather than through libs.versions.toml, because nothing on the classpath has
    // to agree with it — the rule MockServer's image follows doesn't apply here. Note the tag
    // carries no `v`: the git tag is v2.25.0 and the image tag is 2.25.0.
    val GO_HTTPBIN_IMAGE: DockerImageName =
      DockerImageName
        .parse("ghcr.io/mccutchen/go-httpbin")
        .withTag("2.25.0")

    /**
     * A go-httpbin container, configured the way the suites here need it.
     *
     * Shared so the semantics suites can reuse it without repeating the limits, which are the
     * one piece of configuration that isn't obvious: `/delay` and `/drip` are capped by
     * MAX_DURATION and `/bytes` by MAX_BODY_SIZE, and both defaults are low enough that a
     * reasonable test asking for a two-second delay or a two-megabyte body gets a 400 rather
     * than the thing it asked for.
     *
     * Configured by environment rather than by command, since the image is distroless and has
     * no shell to run one with.
     */
    fun goHttpbin(): GenericContainer<*> =
      GenericContainer(GO_HTTPBIN_IMAGE)
        .withExposedPorts(8080)
        .withEnv("MAX_DURATION", "60s")
        .withEnv("MAX_BODY_SIZE", "10485760")
  }
}
