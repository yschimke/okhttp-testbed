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

/**
 * A server this suite depends on and does not operate.
 *
 * Each entry names one server rather than one URL: `tls-ech.dev` publishes four hostnames that
 * four tests use, but they are one machine, and if it is down they are down together. Splitting
 * them would report the same outage four times and probe it four times.
 *
 * The probe says whether the server is *there*, never whether OkHttp is right about it. That
 * distinction is the whole point — a probe that asserted what the tests assert would skip the
 * failures we want to see.
 */
enum class Endpoint(
  val server: String,
  val operator: String,
  val probe: Probe,
) {
  GOOGLE(
    server = "www.google.com",
    operator = "Google",
    probe = Probe.Https("https://www.google.com/robots.txt"),
  ),

  // SniOverrideTest connects to sni.cloudflaressl.com's address while claiming to be
  // cloudflare-dns.com, so it needs both: the name it resolves and the edge it talks to. They fail
  // independently — a resolver that stops answering for one is not the other going away.
  CLOUDFLARE_SNI(
    server = "sni.cloudflaressl.com",
    operator = "Cloudflare",
    probe = Probe.SystemDns("sni.cloudflaressl.com"),
  ),

  CLOUDFLARE_DNS(
    server = "cloudflare-dns.com",
    operator = "Cloudflare",
    probe = Probe.Https("https://cloudflare-dns.com/cdn-cgi/trace"),
  ),

  LETSENCRYPT(
    server = "valid-isrgrootx1.letsencrypt.org",
    operator = "Let's Encrypt",
    probe = Probe.Https("https://valid-isrgrootx1.letsencrypt.org/robots.txt"),
  ),

  // EchTest resolves every name through this, and a captive or filtering resolver is the failure
  // it is most likely to meet on a strange network. Probed as DNS rather than as HTTPS: reaching
  // the endpoint proves nothing if it won't answer a query.
  CLOUDFLARE_DOH(
    server = "1.1.1.1/dns-query",
    operator = "Cloudflare",
    probe = Probe.Doh(url = "https://1.1.1.1/dns-query", name = "cloudflare-ech.com"),
  ),

  CLOUDFLARE_ECH(
    server = "cloudflare-ech.com",
    operator = "Cloudflare",
    probe = Probe.Https("https://cloudflare-ech.com/cdn-cgi/trace"),
  ),

  TLS_ECH_DEV(
    server = "tls-ech.dev",
    operator = "Dennis Jackson",
    probe = Probe.Https("https://tls-ech.dev/"),
  ),

  DEFO_IE(
    server = "defo.ie",
    operator = "the DEfO project",
    probe = Probe.Https("https://defo.ie/ech-check.php"),
  ),
  ;

  /** Stable across renames, because the status page keeps history against it. */
  val id: String get() = name.lowercase().replace('_', '-')
}
