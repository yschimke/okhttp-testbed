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

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import okhttp3.OkHttpClient

/**
 * Probes each [Endpoint] once per JVM, and publishes what it found.
 *
 * A public test server that has gone away should read as *unavailable*, not as OkHttp failing.
 * That is the whole job: the probe decides which of those two a test is about to report, and the
 * report file lets the status page say "Quad9 has been unreachable for three days" next to "the
 * DNS suite is amber".
 *
 * This does not soften the module's rule that failures are recorded rather than hidden. A server
 * that answers gets its tests run and its failures recorded exactly as before — only a server
 * that isn't there produces a skip, and the skip carries the reason.
 */
object Preflight {
  /** Where the run's findings are written. Unset outside Gradle, where nothing collects them. */
  private val reportPath: String? = System.getProperty("testbed.endpoints.report")

  /** Names the file after the Gradle task, since two tasks in one module would collide. */
  private val task: String = System.getProperty("testbed.task", "networkTest")

  private val client: OkHttpClient by lazy { Probe.newProbeClient() }

  private val results = linkedMapOf<Endpoint, Probe.Result>()

  /**
   * The probe's verdict on [endpoint], running it the first time it is asked for.
   *
   * Cached for the life of the JVM rather than re-probed per test: the question is whether the
   * server is there, and asking eight times over one Gradle task tells us nothing the first
   * answer didn't, at eight times the cost to somebody else's server.
   */
  @Synchronized
  fun check(endpoint: Endpoint): Probe.Result {
    results[endpoint]?.let { return it }

    val result = endpoint.probe.run(client)
    // Recorded before the report is written, or the report is always one probe behind.
    results[endpoint] = result
    writeReport()
    return result
  }

  /**
   * Rewritten after each new probe rather than once at the end.
   *
   * A JUnit lifecycle hook that fired at shutdown would be tidier, but it would also be the one
   * thing that doesn't run when a suite dies badly — and the run where everything fell over is
   * the run whose endpoint states are worth having.
   */
  private fun writeReport() {
    val path = reportPath ?: return

    val entries =
      results.entries.joinToString(",\n") { (endpoint, result) ->
        """
        |    {
        |      "id": ${endpoint.id.json()},
        |      "server": ${endpoint.server.json()},
        |      "operator": ${endpoint.operator.json()},
        |      "target": ${endpoint.probe.target.json()},
        |      "state": ${(if (result.up) "up" else "down").json()},
        |      "detail": ${result.detail.json()}
        |    }
        """.trimMargin()
      }

    val probedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    File(path).apply {
      parentFile?.mkdirs()
      writeText(
        """
        |{
        |  "task": ${task.json()},
        |  "probedAt": ${probedAt.json()},
        |  "endpoints": [
        |$entries
        |  ]
        |}
        """.trimMargin() + "\n",
      )
    }
  }

  /**
   * Enough JSON escaping for what actually goes in here: identifiers, hostnames, and exception
   * messages. Written out rather than pulled in, because a test-only dependency on a JSON library
   * would be a dependency the suites carry against every OkHttp version they are pointed at.
   */
  private fun String.json(): String {
    val escaped =
      buildString(length + 2) {
        for (char in this@json) {
          when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
          }
        }
      }
    return "\"$escaped\""
  }
}
