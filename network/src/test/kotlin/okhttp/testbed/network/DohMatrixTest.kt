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
import java.io.IOException
import java.net.UnknownHostException
import okhttp.testbed.network.DohMatrixReport.Answer
import okhttp.testbed.network.DohMatrixReport.Outcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The same name asked at every resolver, and the differences written down.
 *
 * This is the half of the matrix that is a record rather than a test. Quad9 and AdGuard filter,
 * by design and by different rules, so a name that answers at Cloudflare and not at Quad9 is a
 * result about DNS policy — publishing it is the point, and asserting it would be recording a
 * preference as a defect. [MatrixName.expectation] says which of these the suite is willing to
 * fail on: the two names where a disagreement really would mean something is broken.
 *
 * One case per name rather than per resolver, because the row is the unit: a difference is only
 * visible next to what the others said.
 */
class DohMatrixTest {
  @ParameterizedTest
  @EnumSource(MatrixName::class)
  fun record(name: MatrixName) {
    val row =
      DohResolver.entries.associateWith { resolver ->
        ask(resolver, name.hostname).also {
          DohMatrixReport.record(name.hostname, resolver.id, it)
        }
      }

    // Judged only on the resolvers that actually answered a DNS question. Unreachable is an
    // outage, and an HTTP error is the resolver failing rather than the name — both are recorded
    // and neither is evidence about the name, so neither is asserted on.
    val answered = row.filterValues { it.outcome !in setOf(Outcome.UNAVAILABLE, Outcome.ERRORED) }
    assumeTrue(answered.isNotEmpty()) { "no resolver in the matrix answered" }

    val wrong =
      when (name.expectation) {
        Expectation.EVERYONE_RESOLVES -> answered.filterValues { it.outcome != Outcome.RESOLVED }
        Expectation.NOBODY_RESOLVES -> answered.filterValues { it.outcome == Outcome.RESOLVED }
        // Recorded and not judged. A filtering resolver withholding an answer is the answer.
        Expectation.RECORD_ONLY -> emptyMap()
      }

    assertThat(
      wrong.map { (resolver, answer) -> "${resolver.id}: ${answer.outcome.id} ${answer.detail}".trim() },
      name = "${name.hostname} (${name.expectation.name.lowercase().replace('_', ' ')})",
    ).isEmpty()
  }

  private fun ask(
    resolver: DohResolver,
    hostname: String,
  ): Answer {
    val preflight = Preflight.check(resolver.endpoint)
    if (!preflight.up) return Answer(Outcome.UNAVAILABLE, detail = preflight.detail)

    return try {
      val addresses = resolver.dns().lookup(hostname)
      val outcome =
        when {
          addresses.all { it.isAnyLocalAddress } -> Outcome.SINKHOLED
          else -> Outcome.RESOLVED
        }
      Answer(outcome, addresses = addresses.map { it.hostAddress.orEmpty() })
    } catch (e: UnknownHostException) {
      // Withheld and genuinely absent arrive the same way over DoH: the resolver returns no
      // answer and OkHttp raises this. The addresses column is what tells them apart by eye —
      // a filtered name at some resolvers is a name that answers at others.
      //
      // A resolver that answered HTTP rather than DNS arrives this way too, because `Dns.lookup`
      // declares nothing else, so the two are separated on the wrapped failure and not on the
      // type. See [httpFailure].
      when (val httpFailure = e.httpFailure()) {
        null -> Answer(Outcome.UNRESOLVED, detail = e.message.orEmpty())
        else -> Answer(Outcome.ERRORED, detail = httpFailure.message.orEmpty())
      }
    } catch (e: IOException) {
      // Unreachable through `DnsOverHttps`, which wraps everything above. Kept because `ask` is
      // written against the `Dns` interface rather than against one implementation, and an
      // implementation that let an `IOException` out belongs in this column rather than uncaught.
      Answer(Outcome.ERRORED, detail = e.message.orEmpty())
    }
  }

  /**
   * The HTTP failure behind a resolution failure, if there was one.
   *
   * Some resolvers turn a validation failure into an HTTP `502`, which is how this outcome was
   * discovered rather than designed: `sigfail` erroring at one resolver and resolving at another
   * is a real difference, and calling it "no answer" would have filed it under the wrong heading.
   * But it does not arrive as an `IOException` a caller can catch — `DnsOverHttps.throwBestFailure`
   * puts it in `cause`, or in `suppressed` when the other query failed first — so this walks for
   * it. [DnsFailureTest] asserts that shape; this is what reads it.
   *
   * Note the failure can be lost entirely: when the *first* failure is itself an
   * `UnknownHostException`, OkHttp rethrows it and drops the rest, so a name whose A query 502s
   * and whose AAAA query NXDOMAINs may record as `unresolved`. Under-reporting this column rather
   * than over-reporting it is the right way round.
   */
  private fun Throwable.httpFailure(): IOException? =
    generateSequence(this) { it.cause }
      .flatMap { sequenceOf(it) + it.suppressed.asSequence() }
      .filterIsInstance<IOException>()
      .firstOrNull { it !is UnknownHostException }

  /** What a disagreement about this name would mean. */
  enum class Expectation {
    EVERYONE_RESOLVES,
    NOBODY_RESOLVES,
    RECORD_ONLY,
  }

  enum class MatrixName(
    val hostname: String,
    val expectation: Expectation,
  ) {
    /** The control. A resolver that cannot answer this is broken, not opinionated. */
    CONTROL("www.google.com", Expectation.EVERYONE_RESOLVES),

    /** Reserved by RFC 2606 so that it never resolves. The other control. */
    NXDOMAIN("no-such-name.invalid", Expectation.NOBODY_RESOLVES),

    /** Ad and tracking infrastructure — squarely AdGuard's remit and nobody else's. */
    TRACKER("doubleclick.net", Expectation.RECORD_ONLY),

    /** Published by OpenDNS to be classified as malicious, which is Quad9's remit. */
    MALWARE_TEST("www.internetbadguys.com", Expectation.RECORD_ONLY),

    /** Carries an `HTTPS` record, so a resolver that mishandles one shows up here first. */
    ECH_ENABLED("cloudflare-ech.com", Expectation.RECORD_ONLY),

    /**
     * Correctly signed, and the control for the next one: a resolver that cannot answer this is
     * broken rather than strict.
     */
    DNSSEC_SIGNED("sigok.ippacket.stream", Expectation.EVERYONE_RESOLVES),

    /**
     * Deliberately mis-signed, which is how this matrix answers "who validates DNSSEC".
     *
     * A validating resolver withholds an answer; one that does not validate hands over the
     * address. Both are defensible positions and neither is OkHttp's doing, so it is recorded
     * rather than judged — but it is worth knowing which resolver in the matrix is which, because
     * it explains a name that resolves at one and not another with no filtering involved.
     */
    DNSSEC_BOGUS("sigfail.ippacket.stream", Expectation.RECORD_ONLY),
  }
}
