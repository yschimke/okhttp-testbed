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

import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * The DoH resolvers the matrix queries.
 *
 * Each carries its [Endpoint] so a case can consult the preflight for its own resolver and skip
 * rather than fail — a throttled resolver is an outage, not a result about OkHttp.
 *
 * NextDNS is deliberately absent. It is configurable per profile, which is what makes it
 * interesting, but the bare endpoint without a profile answers for nobody in particular; adding
 * it would mean either committing a profile ID or testing a resolver in a state no user is in.
 */
enum class DohResolver(
  val url: String,
  val endpoint: Endpoint,
  private val bootstrap: List<String>,
) {
  /** Addressed by IP, so resolving the resolver needs no resolver. */
  CLOUDFLARE(
    url = "https://1.1.1.1/dns-query",
    endpoint = Endpoint.CLOUDFLARE_DOH,
    bootstrap = listOf("1.1.1.1", "1.0.0.1"),
  ),

  /** A second implementation, addressed by name — which is what makes bootstrapping a question. */
  GOOGLE(
    url = "https://dns.google/dns-query",
    endpoint = Endpoint.GOOGLE_DOH,
    bootstrap = listOf("8.8.8.8", "8.8.4.4"),
  ),

  /** Filters malicious names: the case where an answer is deliberately withheld. */
  QUAD9(
    url = "https://dns.quad9.net/dns-query",
    endpoint = Endpoint.QUAD9_DOH,
    bootstrap = listOf("9.9.9.9", "149.112.112.112"),
  ),

  /** A filtering resolver again, with different rules — so the two can disagree with each other. */
  ADGUARD(
    url = "https://dns.adguard-dns.com/dns-query",
    endpoint = Endpoint.ADGUARD_DOH,
    bootstrap = listOf("94.140.14.14", "94.140.15.15"),
  ),
  ;

  /** Short, stable, and what the recorded matrix is keyed by. */
  val id: String get() = name.lowercase()

  fun bootstrapAddresses(): List<InetAddress> = bootstrap.map(InetAddress::getByName)

  /**
   * A resolver built the way a caller would build it.
   *
   * The bootstrap addresses are supplied for every resolver, not only the name-addressed ones:
   * without them a resolver reached by name falls back to the system resolver, which would make
   * this a test of the platform rather than of OkHttp.
   *
   * Deliberately not a builder: `includeServiceMetadata` exists only in 5.5.0 and later, and this
   * file has to compile against every version the testbed is pointed at. The suite that needs it
   * builds its own and is excluded from the source set below that version. See [builder].
   */
  fun dns(post: Boolean = true): DnsOverHttps = builder().post(post).build()

  /** The common half, so a version-gated suite can add what it needs without repeating this. */
  fun builder(): DnsOverHttps.Builder =
    DnsOverHttps
      .Builder()
      .client(OkHttpClient())
      .url(url.toHttpUrl())
      .bootstrapDnsHosts(bootstrapAddresses())
}
