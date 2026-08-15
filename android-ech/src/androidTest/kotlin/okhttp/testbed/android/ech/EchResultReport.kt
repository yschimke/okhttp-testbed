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
package okhttp.testbed.android.ech

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException

/** ECHConfigLists observed on Android, pulled from the test APK after instrumentation finishes. */
object EchResultReport {
  data class Attempt(
    val source: String,
    val config: ByteArray?,
  )

  private data class Observation(
    val suite: String,
    val case: String,
    val server: String,
    val attempts: List<Attempt>,
  )

  private val observations = linkedMapOf<String, Observation>()

  @Synchronized
  fun record(
    suite: String,
    case: String,
    server: String,
    attempts: List<Attempt>,
  ) {
    observations["$suite|$case"] = Observation(suite, case, server, attempts)
    write()
  }

  private fun write() {
    val report =
      InstrumentationRegistry
        .getInstrumentation()
        .context.filesDir
        .resolve("ech-results.json")

    val rows =
      observations.values.joinToString(",\n") { observation ->
        val attempts =
          observation.attempts.joinToString(", ") { attempt ->
            val config =
              attempt.config
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP).json() }
                ?: "null"
            """{"source":${attempt.source.json()},"echConfigList":$config}"""
          }
        """    {"suite":${observation.suite.json()},"case":${observation.case.json()},"server":${observation.server.json()},"platform":"ANDROID","attempts":[$attempts]}"""
      }

    // `filesDir` names a directory; it doesn't promise one exists. Nothing creates it for an
    // instrumentation APK that is never launched as an app, so the first write lands on a
    // missing parent and fails with ENOENT — which is what the JVM report's `mkdirs()` is for.
    //
    // Catching that failure rather than letting it out is the more important half. `record` is
    // called before a case makes its assertions, so that evidence survives one; the same
    // ordering means a throw here replaces every real result with this one. It did: both
    // suites reported nothing but
    //
    //     java.io.FileNotFoundException:
    //         /data/user/0/okhttp.testbed.android.ech.test/files/ech-results.json:
    //         open failed: ENOENT (No such file or directory)
    //
    // for all nine cases. Evidence is a by-product of a test, and losing it is worth a line in
    // the log, not the result of the run. `run-ech-test.sh` already drops a report it can't
    // read, and `collect_results.py` reads whichever reports it finds.
    try {
      report.parentFile?.mkdirs()
      report.writeText("{\n  \"observations\": [\n$rows\n  ]\n}\n")
    } catch (e: IOException) {
      Log.w("EchResultReport", "Failed to write $report", e)
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
