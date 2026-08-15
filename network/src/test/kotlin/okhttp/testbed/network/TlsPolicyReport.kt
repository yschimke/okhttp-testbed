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
 * Revocation, pinning and Certificate Transparency — what each platform actually does.
 *
 * These are the three checks users assume are happening and that mostly are not. The JVM does not
 * check revocation unless `com.sun.net.ssl.checkRevocation` is set and PKIX options say how;
 * Android's behaviour varies by release; OkHttp enforces no SCTs at all. None of that is a defect
 * in OkHttp and none of it is assertable as pass or fail — a suite claiming
 * `revoked.badssl.com` "must" be refused would be asserting a policy the platform never promised.
 *
 * So this records, per platform and per version, and the answer is the deliverable. The platform
 * is recorded alongside because that is the axis the answers actually vary on.
 */
object TlsPolicyReport {
  private val reportPath: String? = System.getProperty("testbed.tlspolicy.report")

  private val task: String = System.getProperty("testbed.task", "networkTest")

  private val checks = linkedMapOf<String, Check>()

  /**
   * One question and what happened.
   *
   * [accepted] is the fact — did the connection complete — and [detail] is how it went wrong when
   * it did not. Deliberately not a pass or a fail: whether accepting is correct depends on the
   * platform's own promises, and the page shows the answer rather than a verdict on it.
   */
  data class Check(
    val accepted: Boolean,
    val detail: String = "",
  )

  @Synchronized
  fun record(
    question: String,
    check: Check,
  ) {
    checks[question] = check
    write()
  }

  private fun write() {
    val path = reportPath ?: return

    val body =
      checks.entries.joinToString(",\n") { (question, check) ->
        """    ${question.json()}: {"accepted": ${check.accepted}, "detail": ${check.detail.json()}}"""
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
        |  "checks": {
        |$body
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
