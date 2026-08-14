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

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * How one [Endpoint] is checked for being there.
 *
 * Every probe runs on a stock [OkHttpClient] with short timeouts and no overrides. That is
 * deliberate: the tests configure OkHttp in ways that are the subject of the test, and a probe
 * carrying the same configuration could not tell "the server is gone" apart from "the thing we
 * are testing is broken". A default client answers the narrower question the preflight asks.
 */
sealed interface Probe {
  /** What to say when this probe fails, in the skip reason and on the status page. */
  val target: String

  fun run(client: OkHttpClient): Result

  /**
   * Reachable over HTTPS.
   *
   * Any response below 500 counts, including a 404: several of these URLs are chosen because the
   * handshake is the interesting part and the body is not. A 5xx is the server telling us it is
   * unwell, which is the case this is here to catch.
   */
  data class Https(
    val url: String,
  ) : Probe {
    override val target: String get() = url

    override fun run(client: OkHttpClient): Result =
      attempt {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
          if (response.code < 500) {
            Result.up()
          } else {
            Result.down("answered HTTP ${response.code}")
          }
        }
      }
  }

  /**
   * Resolves through the system resolver to at least one address.
   *
   * Named [SystemDns] rather than `Dns` so that `Dns` inside this file is unambiguously OkHttp's.
   */
  data class SystemDns(
    val host: String,
  ) : Probe {
    override val target: String get() = host

    override fun run(client: OkHttpClient): Result =
      attempt {
        val addresses = okhttp3.Dns.SYSTEM.lookup(host)
        if (addresses.isNotEmpty()) {
          Result.up()
        } else {
          Result.down("resolved to no addresses")
        }
      }
  }

  /** Answers a DNS query over HTTPS for a name we know exists. */
  data class Doh(
    val url: String,
    val name: String,
  ) : Probe {
    override val target: String get() = url

    override fun run(client: OkHttpClient): Result =
      attempt {
        val resolver =
          DnsOverHttps
            .Builder()
            .client(client)
            .url(url.toHttpUrl())
            .build()

        val addresses = resolver.lookup(name)
        if (addresses.isNotEmpty()) {
          Result.up()
        } else {
          Result.down("resolved $name to no addresses")
        }
      }
  }

  /** What a probe found, and why, in a form the report and the skip reason can both use. */
  data class Result(
    val up: Boolean,
    val detail: String,
  ) {
    companion object {
      fun up(): Result = Result(up = true, detail = "")

      fun down(detail: String): Result = Result(up = false, detail = detail)
    }
  }

  companion object {
    /**
     * A probe answers one question — is it there — so every way of not being there collapses to
     * the same answer. An unreachable host, a handshake that won't complete and a resolver that
     * won't answer are all `down`; only the detail differs, and the detail is what gets reported.
     */
    internal inline fun attempt(body: () -> Result): Result =
      try {
        body()
      } catch (e: IOException) {
        Result.down(e.describe())
      } catch (e: RuntimeException) {
        // A malformed URL or a resolver refusing the request arrives as an unchecked exception,
        // and it is still the endpoint being unusable rather than this suite being broken.
        Result.down(e.describe())
      }

    private fun Throwable.describe(): String = "${javaClass.simpleName}: ${message ?: "no detail"}"

    /**
     * Short timeouts on purpose. A probe exists to get out of the way; a black-holed host should
     * cost the run seconds, not the two minutes a default connect timeout would spend before
     * reaching the same conclusion.
     */
    fun newProbeClient(): OkHttpClient =
      OkHttpClient
        .Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
  }
}
