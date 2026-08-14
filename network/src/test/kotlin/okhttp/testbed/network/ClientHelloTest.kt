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
import assertk.assertions.isIn
import assertk.assertions.isNotNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test

/**
 * What OkHttp offers in its ClientHello, recorded rather than judged.
 *
 * How's My SSL answers with the suites, groups and signature algorithms it was offered, plus its
 * own rating. It offers that as an API for testing clients you control, which one request per
 * scheduled run is — the `network` workflow does not run on pull requests, so this calls the
 * service once a day rather than once a commit.
 *
 * Almost all of it is recorded and not asserted. The offered suite list is the platform's
 * decision far more than OkHttp's, and pinning it here would turn every JDK update into a failed
 * test rather than the observation it should be. Two things are worth asserting outright, and
 * they are the two the issue names: the negotiated version is at least 1.2, and the rating is not
 * "Bad". Anything further would be asserting on somebody else's classifier.
 *
 * The value is the trend. A ClientHello that shifts between OkHttp releases changes how CDNs and
 * bot-detection systems treat every application using it, and "the API started returning 403
 * after we upgraded" is how users find out today.
 */
@RequiresEndpoint(Endpoint.HOWSMYSSL)
class ClientHelloTest {
  private val client = OkHttpClient()

  @Test
  fun recordWhatTheHandshakeOffered() {
    val body =
      client.newCall(Request.Builder().url(CHECK_URL.toHttpUrl()).build()).execute().use { response ->
        assertThat(response.code, name = "How's My SSL").isEqualTo(200)
        response.body.string()
      }

    ClientHelloReport.record("howsmyssl", body)

    // Read out of the raw JSON rather than parsed into a model: two string fields do not justify
    // a JSON dependency in a module whose suites have to build against any OkHttp version.
    val tlsVersion = body.stringField("tls_version")
    val rating = body.stringField("rating")

    assertThat(tlsVersion, name = "negotiated TLS version").isNotNull().isIn("TLS 1.2", "TLS 1.3")

    // "Bad" is the service's own word for a handshake with a known weakness in it. "Improvable"
    // is not a failure: it is what a client that still offers CBC suites for compatibility gets,
    // and that is a platform decision worth recording rather than failing on.
    assertThat(rating, name = "How's My SSL rating").isNotNull().isIn("Probably Okay", "Improvable")
  }

  private fun String.stringField(name: String): String? =
    Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"([^\"]*)\"").find(this)?.groupValues?.get(1)

  companion object {
    private const val CHECK_URL = "https://www.howsmyssl.com/a/check"
  }
}
