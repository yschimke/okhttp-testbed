/*
 * Copyright (C) 2026 Block, Inc.
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
package okhttp.testbed.android.ech

import android.os.Build
import android.util.Log
import androidx.test.platform.io.PlatformTestStorageRegistry
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The Android CT result consumed by the status site's cross-platform TLS policy table. */
object AndroidTlsPolicyReport {
  private data class Observation(
    val accepted: Boolean,
    val detail: String,
  )

  private var enforced: Observation? = null
  private var optOut: Observation? = null

  @Synchronized
  fun recordEnforced(
    accepted: Boolean,
    detail: String,
  ) {
    enforced = Observation(accepted, detail)
    write()
  }

  @Synchronized
  fun recordOptOut(
    accepted: Boolean,
    detail: String,
  ) {
    optOut = Observation(accepted, detail)
    write()
  }

  private fun write() {
    val enforced = enforced
    val accepted = enforced?.accepted ?: true
    val detail =
      listOfNotNull(
        enforced?.let { "enforced: ${it.detail}" },
        optOut?.let { "opt-out control: ${it.detail}" },
      ).joinToString("; ")

    try {
      PlatformTestStorageRegistry
        .getInstance()
        .openOutputFile("tls-policy.json")
        .bufferedWriter()
        .use {
          it.write(
            """
            |{
            |  "task": "connectedAndroidTest",
            |  "recordedAt": ${timestamp().json()},
            |  "javaVersion": ${"Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})".json()},
            |  "javaVendor": "Android Runtime",
            |  "checks": {
            |    "Certificate Transparency": {"accepted": $accepted, "detail": ${detail.json()}}
            |  }
            |}
            """.trimMargin() + "\n",
          )
        }
    } catch (e: IOException) {
      Log.w("AndroidTlsPolicy", "Failed to write tls-policy.json", e)
    }
  }

  private fun timestamp(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("UTC") }
      .format(Date())

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
