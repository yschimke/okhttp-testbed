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
 * Which origins offer HTTP/3, and what OkHttp used instead.
 *
 * A record rather than a result. OkHttp has no HTTP/3, so "negotiated h2 at an origin advertising
 * h3" is the correct outcome today and an uninteresting assertion — what is worth publishing is
 * the gap: how much of the web is offering a protocol this client cannot take, and whether that
 * changes. On the day HTTP/3 lands, this table is where it shows up.
 *
 * Written like the other records here: one file per Gradle task, rewritten after every row, and
 * hand-rolled JSON rather than a dependency this module would not otherwise have.
 */
object AltSvcReport {
  private val reportPath: String? = System.getProperty("testbed.altsvc.report")

  private val task: String = System.getProperty("testbed.task", "networkTest")

  private val rows = linkedMapOf<String, Row>()

  /**
   * One origin's answer.
   *
   * [altSvc] is the header verbatim, empty when the origin offered nothing — the two are different
   * results and collapsing them would lose the only interesting column. [protocol] is what OkHttp
   * actually negotiated, which is the other one.
   */
  data class Row(
    val protocol: String,
    val altSvc: String,
    val advertisesH3: Boolean,
  )

  @Synchronized
  fun record(
    origin: String,
    row: Row,
  ) {
    rows[origin] = row
    write()
  }

  private fun write() {
    val path = reportPath ?: return

    val origins =
      rows.entries.joinToString(",\n") { (origin, row) ->
        """    ${origin.json()}: {""" +
          """"protocol": ${row.protocol.json()}, """ +
          """"altSvc": ${row.altSvc.json()}, """ +
          """"advertisesH3": ${row.advertisesH3}}"""
      }

    File(path).apply {
      parentFile?.mkdirs()
      writeText(
        """
        |{
        |  "task": ${task.json()},
        |  "recordedAt": ${Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().json()},
        |  "origins": {
        |$origins
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
