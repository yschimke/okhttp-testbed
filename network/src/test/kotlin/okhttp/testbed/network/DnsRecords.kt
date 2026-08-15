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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns

/**
 * `newCall`, waited on.
 *
 * `lookup` answers with addresses alone, which is all RFC 1035 has to offer; everything RFC 9460
 * added — the ALPN list, a port, address hints, the ECH config — arrives only through `newCall`,
 * which is asynchronous and may report in more than one batch. So the records are collected until
 * it says it is done.
 *
 * A timeout fails rather than hanging: a resolver that never called back would otherwise stall
 * the run instead of reporting.
 *
 * Left out of the build below OkHttp 5.5.0 along with the suites that use it — `Dns.Record` does
 * not exist there.
 */
fun Dns.records(
  hostname: String,
  timeoutSeconds: Long = 30,
): List<Dns.Record> {
  val latch = CountDownLatch(1)
  val collected = mutableListOf<Dns.Record>()
  val failure = AtomicReference<IOException>()

  newCall(Dns.Request(hostname)).enqueue(
    object : Dns.Callback {
      override fun onRecords(
        call: Dns.Call,
        last: Boolean,
        records: List<Dns.Record>,
      ) {
        synchronized(collected) { collected += records }
        if (last) latch.countDown()
      }

      override fun onFailure(
        call: Dns.Call,
        e: IOException,
      ) {
        failure.set(e)
        latch.countDown()
      }
    },
  )

  check(latch.await(timeoutSeconds, TimeUnit.SECONDS)) { "$hostname: the resolver never called back" }
  failure.get()?.let { throw it }

  return synchronized(collected) { collected.toList() }
}
