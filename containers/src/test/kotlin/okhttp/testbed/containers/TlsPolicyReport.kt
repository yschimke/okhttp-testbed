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

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The controlled no-SCT fixture's contribution to the cross-platform TLS policy report. */
object TlsPolicyReport {
  private val reportPath: String? = System.getProperty("testbed.tlspolicy.report")

  data class Check(
    val accepted: Boolean,
    val detail: String,
  )

  fun record(check: Check) {
    val path = reportPath ?: return
    File(path).apply {
      parentFile?.mkdirs()
      writeText(
        """
        |{
        |  "task": "test",
        |  "recordedAt": ${Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().json()},
        |  "javaVersion": ${System.getProperty("java.version").orEmpty().json()},
        |  "javaVendor": ${System.getProperty("java.vm.name").orEmpty().json()},
        |  "checks": {
        |    "Certificate Transparency": {"accepted": ${check.accepted}, "detail": ${check.detail.json()}}
        |  }
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
