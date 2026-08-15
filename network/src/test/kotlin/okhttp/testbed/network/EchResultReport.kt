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
import okio.ByteString

/** The ECH configuration evidence used by a test, kept separately from its pass/fail result. */
object EchResultReport {
  private val reportPath: String? = System.getProperty("testbed.ech.report")
  private val task: String = System.getProperty("testbed.task", "echTest")
  private val observations = linkedMapOf<String, Observation>()

  data class Attempt(
    val source: String,
    val config: ByteString?,
  )

  private data class Observation(
    val suite: String,
    val case: String,
    val server: String,
    val platform: String,
    val attempts: List<Attempt>,
  )

  /** Record immediately after a connection, so evidence survives a later assertion failure. */
  @Synchronized
  fun record(
    suite: String,
    case: String,
    server: String,
    platform: String,
    attempts: List<Attempt>,
  ) {
    observations["$suite|$case|$platform"] = Observation(suite, case, server, platform, attempts)
    write()
  }

  private fun write() {
    val path = reportPath ?: return
    val rows =
      observations.values.joinToString(",\n") { observation ->
        val attempts =
          observation.attempts.joinToString(", ") { attempt ->
            val config = attempt.config?.base64()?.json() ?: "null"
            """{"source":${attempt.source.json()},"echConfigList":$config}"""
          }
        """    {"suite":${observation.suite.json()},"case":${observation.case.json()},"server":${observation.server.json()},"platform":${observation.platform.json()},"attempts":[$attempts]}"""
      }

    File(path).apply {
      parentFile?.mkdirs()
      writeText(
        """
        |{
        |  "task": ${task.json()},
        |  "recordedAt": ${Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().json()},
        |  "observations": [
        |$rows
        |  ]
        |}
        """.trimMargin() + "\n",
      )
    }
  }

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
