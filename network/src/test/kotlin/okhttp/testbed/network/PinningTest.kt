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
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Pinning, revocation and Certificate Transparency — asserted where they are promises, recorded
 * where they are not.
 *
 * These are the three checks users assume are happening, and only one of them is OkHttp's to make.
 * `CertificatePinner` is a promise OkHttp keeps itself, so it is asserted. Revocation is the
 * platform's — the JVM does not check it unless asked, and Android varies by release — and
 * Certificate Transparency is nobody's here, since OkHttp enforces no SCTs. Asserting either
 * would be writing down a policy that was never offered, so they are recorded instead, per
 * platform, and the answer is the deliverable.
 *
 * The positive pinning case is deliberately absent: pinning against a live public chain means
 * pinning something that rotates, and a suite that goes red when Cloudflare renews a certificate
 * teaches everyone to ignore it. `FixturePinningTest` does that half against a chain this
 * repository controls.
 */
class PinningTest {
  /**
   * A wrong pin refuses the connection, and says what the peer actually presented.
   *
   * Both halves matter. Refusing is the promise; the message is what makes the promise usable,
   * because the pin a caller needs is exactly the one they got wrong, and an exception without it
   * sends them to a search engine rather than to the fix.
   */
  @Test
  fun aWrongPinIsRefusedAndListsTheRealPins() {
    assumeAvailable(Endpoint.BADSSL_PINNING)

    val client =
      OkHttpClient
        .Builder()
        .certificatePinner(
          CertificatePinner
            .Builder()
            .add(Endpoint.BADSSL_PINNING.server, WRONG_PIN)
            .build(),
        ).build()

    val (result, failure) =
      try {
        client
          .newCall(Request.Builder().url("https://${Endpoint.BADSSL_PINNING.server}/").build())
          .execute()
          .use { TlsPolicyReport.Check(accepted = true, detail = "HTTP ${it.code}") to null }
      } catch (e: IOException) {
        TlsPolicyReport.Check(accepted = false, detail = "${e.javaClass.simpleName}: ${e.message.orEmpty()}") to e
      }

    TlsPolicyReport.record(TlsPolicyReport.PINNING, result)

    if (result.accepted) throw AssertionError("a wrong pin was accepted: ${result.detail}")
    checkNotNull(failure)
    assertThat(failure, name = "a deliberately wrong pin").isInstanceOf(SSLPeerUnverifiedException::class)

    val message = failure.message.orEmpty()
    assertThat(message, name = "the failure names the pin that was expected").contains(WRONG_PIN)

    // The peer's own pins, which is the part a caller can act on: the message quotes the whole
    // chain, leaf first, and any pin in it other than ours is the answer they need. Found by
    // scanning rather than by position — the chain is printed *before* the configured pin, and
    // that ordering is presentation rather than promise.
    val peerPins = PIN.findAll(message).map { it.value }.filter { it != WRONG_PIN }.toList()
    assertThat(peerPins, name = "the failure lists the peer's certificate pins").isNotEmpty()
  }

  /**
   * What this platform does with a revoked certificate. Recorded, not asserted.
   *
   * The JVM does not check revocation unless `com.sun.net.ssl.checkRevocation` is set *and* the
   * PKIX parameters say how, so the expected answer on a stock JDK is "connected" — which looks
   * alarming and is exactly what the platform documents. Android's answer differs by release, and
   * Conscrypt has its own path. Writing a `must be refused` assertion here would produce a red
   * suite that is reporting the JDK's documented behaviour as a defect.
   */
  @Test
  fun revocationBehaviourIsRecorded() {
    assumeAvailable(Endpoint.BADSSL_REVOKED)

    val outcome = attempt("https://${Endpoint.BADSSL_REVOKED.server}/")
    TlsPolicyReport.record(TlsPolicyReport.REVOCATION, outcome)

    assumeDefinite(outcome, Endpoint.BADSSL_REVOKED)
  }

  private fun attempt(url: String): TlsPolicyReport.Check =
    try {
      OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use {
        TlsPolicyReport.Check(accepted = true, detail = "HTTP ${it.code}")
      }
    } catch (e: IOException) {
      TlsPolicyReport.Check(accepted = false, detail = "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
    }

  /**
   * A timeout is not an answer, so it skips rather than fails.
   *
   * These two cases record what the platform did; a read timeout records what badssl.com's
   * bandwidth did. Failing on it would put an outage in a column meant for policy — the same
   * reason the preflight exists, arriving too late for the preflight to catch it. The record is
   * written first either way, so a run that skipped still says what it saw.
   */
  private fun assumeDefinite(
    outcome: TlsPolicyReport.Check,
    endpoint: Endpoint,
  ) {
    val timedOut = !outcome.accepted && outcome.detail.contains("Timeout", ignoreCase = true)
    assumeTrue(!timedOut) { "${endpoint.server} timed out, which answers nothing: ${outcome.detail}" }
  }

  private fun assumeAvailable(endpoint: Endpoint) {
    val result = Preflight.check(endpoint)
    assumeTrue(result.up) { "${endpoint.server} is unavailable: ${result.detail}" }
  }

  private companion object {
    /**
     * A syntactically valid pin of nothing at all.
     *
     * Not a real certificate's pin, deliberately: a pin belonging to some other host would fail
     * for the right reason today and could start passing if that host ever appeared in the chain.
     */
    const val WRONG_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    /** A pin as `CertificatePinner` prints it: base64 of a SHA-256, so 43 characters and `=`. */
    val PIN = Regex("sha256/[A-Za-z0-9+/]{43}=")
  }
}
