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

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The `HTTPS` records a resolver returns, and what OkHttp makes of them.
 *
 * `DnsOverHttps` answers `lookup` with addresses alone, which is all RFC 1035 has to offer. The
 * `HTTPS` record (RFC 9460) carries more — the ALPN identifiers a server supports, a port, address
 * hints, and the ECH config list — and OkHttp surfaces it through `newCall`, as
 * `Dns.Record.ServiceMetadata` alongside the `Dns.Record.IpAddress` entries.
 *
 * That richer API is what the ECH work depends on: an ECH config list has nowhere else to come
 * from. This suite asks the DNS half of that question directly rather than through a handshake,
 * so a resolver that stopped returning `HTTPS` records is distinguishable from a TLS stack that
 * stopped using them.
 *
 * `includeServiceMetadata` defaults to on, so the `HTTPS` query goes out whether a caller asked
 * for it or not — worth knowing, since it is a second question per lookup at every resolver here.
 *
 * Only the two non-filtering resolvers are asserted against. Quad9 and AdGuard may legitimately
 * answer differently — that is why they are in the matrix — and turning their filtering into a
 * failed assertion would be recording a preference as a defect.
 *
 * Left out of the build entirely below OkHttp 5.5.0: `includeServiceMetadata` does not exist
 * there, and the suites here have to compile against whatever version the testbed is pointed at.
 */
class DohServiceMetadataTest {
  @ParameterizedTest
  @EnumSource(DohResolver::class, names = ["CLOUDFLARE", "GOOGLE"])
  fun httpsRecordCarriesTheServiceParameters(resolver: DohResolver) {
    val records = resolver.availableWithMetadata().records(ECH_ENABLED)

    val metadata = records.filterIsInstance<Dns.Record.ServiceMetadata>()
    assertThat(metadata, name = "${resolver.id} HTTPS records for $ECH_ENABLED").isNotEmpty()

    val first = metadata.first()
    // Nullable, because an HTTPS record need not list ALPN. cloudflare-ech.com does, and a record
    // that arrived carrying nothing would answer the question here with a false yes.
    assertThat(first.alpnIds, name = "${resolver.id} ALPN ids").isNotNull().isNotEmpty()

    // The reason the record matters here: an ECH config list has nowhere else to come from.
    assertThat(first.echConfigList, name = "${resolver.id} ECH config list").isNotNull()

    // Addresses still arrive alongside. Asking for the metadata must not cost the resolution.
    assertThat(
      records.filterIsInstance<Dns.Record.IpAddress>(),
      name = "${resolver.id} addresses for $ECH_ENABLED",
    ).isNotEmpty()
  }

  /**
   * A name with no `HTTPS` record still resolves.
   *
   * This is the regression that would matter most and show least: asking for service metadata
   * must not turn an ordinary name into a failure. Note it asserts addresses arrive, not that
   * metadata is absent — whether a given name has an `HTTPS` record is its operator's business
   * and can change without warning.
   */
  @ParameterizedTest
  @EnumSource(DohResolver::class, names = ["CLOUDFLARE", "GOOGLE"])
  fun metadataRequestDoesNotBreakAnOrdinaryName(resolver: DohResolver) {
    val records = resolver.availableWithMetadata().records(DohResolverTest.DUAL_STACK)

    assertThat(
      records.filterIsInstance<Dns.Record.IpAddress>(),
      name = "${resolver.id} addresses for ${DohResolverTest.DUAL_STACK}",
    ).isNotEmpty()
  }

  /**
   * The flag turns it off, and addresses survive that.
   *
   * `includeServiceMetadata` defaults to *on*, so the assertions above would pass whether OkHttp
   * honoured the setting or ignored it entirely. Asserting the off case is what distinguishes
   * those: a build that always queried `HTTPS` would fail here and nowhere else.
   */
  @ParameterizedTest
  @EnumSource(DohResolver::class, names = ["CLOUDFLARE", "GOOGLE"])
  fun metadataCanBeTurnedOff(resolver: DohResolver) {
    assumeAvailable(resolver)
    val records = resolver.builder().includeServiceMetadata(false).build().records(ECH_ENABLED)

    assertThat(
      records.filterIsInstance<Dns.Record.ServiceMetadata>(),
      name = "${resolver.id} metadata with includeServiceMetadata off",
    ).isEmpty()

    assertThat(
      records.filterIsInstance<Dns.Record.IpAddress>(),
      name = "${resolver.id} addresses with includeServiceMetadata off",
    ).isNotEmpty()
  }

  /**
   * `newCall` is asynchronous and may report in more than one batch, so the records are collected
   * until it says it is done. A timeout fails rather than hanging: a resolver that never called
   * back would otherwise stall the run rather than report.
   */
  private fun Dns.records(hostname: String): List<Dns.Record> {
    val latch = CountDownLatch(1)
    val collected = mutableListOf<Dns.Record>()
    val failure = AtomicReference<IOException>()

    newCall(Dns.Request(hostname)).enqueue(
      object : Dns.Callback {
        override fun onRecords(
          call: Dns.Call,
          done: Boolean,
          records: List<Dns.Record>,
        ) {
          synchronized(collected) { collected += records }
          if (done) latch.countDown()
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

    check(latch.await(30, TimeUnit.SECONDS)) { "$hostname: the resolver never called back" }
    failure.get()?.let { throw it }

    return synchronized(collected) { collected.toList() }
  }

  private fun DohResolver.availableWithMetadata(): Dns {
    assumeAvailable(this)
    return builder().includeServiceMetadata(true).build()
  }

  private fun assumeAvailable(resolver: DohResolver) {
    val result = Preflight.check(resolver.endpoint)
    assumeTrue(result.up) { "${resolver.endpoint.server} is unavailable: ${result.detail}" }
  }

  companion object {
    /** Publishes an `HTTPS` record carrying an ECH config list — the reason this API exists. */
    const val ECH_ENABLED = "cloudflare-ech.com"
  }
}
