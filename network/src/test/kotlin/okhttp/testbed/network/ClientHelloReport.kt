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
 * What OkHttp put in its ClientHello, as a server saw it.
 *
 * Every other suite here asserts what OkHttp *accepts*. This records what it *sends*, which is
 * what CDNs and bot-detection systems actually key on — a JA3 that shifts between releases can
 * change how Cloudflare or Akamai treat every OkHttp application, and today nobody can point at
 * the change. So this is a record first and an assertion second.
 *
 * The record is written verbatim: the observing service already answers in JSON, and storing its
 * answer unedited means the page shows what the service said rather than what this suite chose
 * to keep. Reformatting it would be the one way to lose the field nobody thought to extract.
 *
 * The platform is recorded alongside, because most of a ClientHello is the platform's rather
 * than OkHttp's — the same OkHttp on JDK 21 and on Android API 37 does not offer the same thing.
 */
object ClientHelloReport {
  private val reportPath: String? = System.getProperty("testbed.clienthello.report")

  private val task: String = System.getProperty("testbed.task", "networkTest")

  private val observations = linkedMapOf<String, String>()

  /**
   * Record one service's view, [rawJson] exactly as it answered.
   *
   * Rewritten on each observation rather than once at the end, for the same reason the endpoint
   * preflight is: the run where something fell over is the run whose record is worth having.
   */
  @Synchronized
  fun record(
    service: String,
    rawJson: String,
  ) {
    observations[service] = rawJson
    write()
  }

  private fun write() {
    val path = reportPath ?: return

    val entries =
      observations.entries.joinToString(",\n") { (service, json) ->
        """    ${service.json()}: ${json.trim()}"""
      }

    File(path).apply {
      parentFile?.mkdirs()
      writeText(
        """
        |{
        |  "task": ${task.json()},
        |  "recordedAt": ${Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().json()},
        |  "javaVersion": ${System.getProperty("java.version").orEmpty().json()},
        |  "javaVendor": ${System.getProperty("java.vm.name").orEmpty().json()},
        |  "observed": {
        |$entries
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
