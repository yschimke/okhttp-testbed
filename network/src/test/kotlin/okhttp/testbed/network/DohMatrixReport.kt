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

/**
 * What each resolver said about each name.
 *
 * The disagreement is the product here, not a failure to be asserted away. Quad9 and AdGuard
 * filter; a name they withhold and Cloudflare answers is a policy difference, and a suite that
 * turned it into a red case would be publishing a preference as a defect. So this records the row
 * and the assertions stay on the two names where every resolver has to agree.
 *
 * Written like the other reports here: one file per Gradle task, rewritten after every row, and
 * hand-rolled JSON rather than a dependency the module would otherwise not have.
 */
object DohMatrixReport {
  private val reportPath: String? = System.getProperty("testbed.doh.report")

  private val task: String = System.getProperty("testbed.task", "networkTest")

  /** Name → resolver id → what that resolver said. Insertion-ordered so the page reads stably. */
  private val rows = linkedMapOf<String, MutableMap<String, Answer>>()

  /**
   * One resolver's answer.
   *
   * [addresses] is empty for everything but [Outcome.RESOLVED], and [detail] carries the exception
   * for [Outcome.UNRESOLVED] or the preflight's reason for [Outcome.UNAVAILABLE] — a resolver that
   * was rate-limited said nothing, which is not the same as a resolver that withheld an answer.
   */
  data class Answer(
    val outcome: Outcome,
    val addresses: List<String> = emptyList(),
    val detail: String = "",
  )

  enum class Outcome {
    RESOLVED,

    /**
     * Answered, but with the unspecified address.
     *
     * This is the outcome worth having a name for. A filtering resolver may withhold an answer, or
     * it may answer `0.0.0.0` — and the second arrives at a caller as a *successful* lookup that
     * then fails to connect, which is a far less obvious failure than a resolution error.
     */
    SINKHOLED,

    UNRESOLVED,

    /**
     * The resolver answered with an HTTP error rather than with DNS.
     *
     * Not the same as a name that failed to resolve, and worth its own column: a validating
     * resolver that turns SERVFAIL into `502` is the resolver failing rather than the name. It
     * does not *reach* a caller as anything distinguishable, though — `Dns.lookup` declares
     * `UnknownHostException` and OkHttp wraps the HTTP failure in one — so code catching that to
     * mean "bad name" mishandles it, and only the cause says otherwise. `DnsFailureTest` asserts
     * that shape; this records which resolvers actually behave this way.
     */
    ERRORED,

    UNAVAILABLE,
    ;

    val id: String get() = name.lowercase()
  }

  @Synchronized
  fun record(
    hostname: String,
    resolver: String,
    answer: Answer,
  ) {
    rows.getOrPut(hostname) { linkedMapOf() }[resolver] = answer
    write()
  }

  private fun write() {
    val path = reportPath ?: return

    val names =
      rows.entries.joinToString(",\n") { (hostname, answers) ->
        val body =
          answers.entries.joinToString(",\n") { (resolver, answer) ->
            val addresses = answer.addresses.joinToString(", ") { it.json() }
            """      ${resolver.json()}: {""" +
              """"outcome": ${answer.outcome.id.json()}, """ +
              """"addresses": [$addresses], """ +
              """"detail": ${answer.detail.json()}}"""
          }
        "    ${hostname.json()}: {\n$body\n    }"
      }

    File(path).apply {
      parentFile?.mkdirs()
      writeText(
        """
        |{
        |  "task": ${task.json()},
        |  "recordedAt": ${Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().json()},
        |  "names": {
        |$names
        |  }
        |}
        """.trimMargin() + "\n",
      )
    }
  }

  /** As in Preflight: enough escaping for identifiers, and no dependency to carry. */
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
