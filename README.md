OkHttp Testbed
==============

Tests for [OkHttp][okhttp] features that need real infrastructure standing behind them —
containers to run against, or a network to reach out over. They are kept out of the OkHttp
repository because they can't run in a normal build: they need Docker, they need outbound
network, and they can fail for reasons that have nothing to do with a change under review.

The testbed runs against **published** OkHttp artifacts rather than a local build, on a
schedule, and publishes the current status.

Suites
------

| Suite         | What it needs               | What it covers                                                      |
|---------------|-----------------------------|---------------------------------------------------------------------|
| `containers`  | Docker                      | SOCKS5 and HTTP proxies, TLS via MockServer, HTTP semantics via go-httpbin, chains that must be rejected, hostile responses, virtual threads (Loom) |
| `network`     | Outbound network            | ALPN and SNI overrides, Let's Encrypt trust, hostile responses in public, ECH on the public servers |
| `android-ech` | Docker, emulators from API 21 to 37 | Encrypted Client Hello over DoH: accepted, retried, and declined, plus the public servers |

The `network` suites call servers other people operate — Google, Cloudflare, Let's Encrypt,
and the ECH test servers at `tls-ech.dev` and `defo.ie`. They came from OkHttp's
`android-test`, where they were tagged `Remote` and excluded from that build for the same
reason they are here rather than there: they need the internet, and they can fail for
reasons that have nothing to do with a change under review.

More of them are planned. The survey they are being drawn from — which public HTTP, TLS and
DNS test servers exist, what each one is good for, and what it is not — is published as a
[topic page][test-servers], and broken down into issues under the
[roadmap tracking issue][roadmap].

Fixtures
--------

Two of them, both containers, both here rather than depended on:

| Fixture       | What it is                                                                          |
|---------------|-------------------------------------------------------------------------------------|
| `ech-fixture` | The origin and DoH resolver the `android-ech` suite runs against. See below.          |
| `test-server` | The testbed's own HTTP and TLS server, and the compose stack around it.               |

`test-server` is what the suites assert both positive and negative results against, and the
one endpoint that reports what a client's handshake actually looked like: a CA it generates itself so a
test can assert a chain *is* accepted, `/tls` reporting the negotiated handshake and the
ClientHello it came from, a port per TLS version the way badssl.com does it, a port per chain that
must be *rejected* — expired, wrong host, self-signed, untrusted root, incomplete — and a set
of responses that are wrong on purpose: resets, truncated bodies, invalid framing. It is a Go
program with nothing outside the standard library behind it, deliberately not built on
OkHttp, because a server sharing the client's framing and TLS stack cannot say whether that
client is acceptable to anything else. It ships alongside pinned `go-httpbin` and Caddy
containers, which is issue #8. See [`test-server/README.md`](test-server/README.md) — it
also covers what a deployment on a non-standard port does and does not change.

The same `go-httpbin` image is also driven directly by the `containers` suite, through
Testcontainers rather than through compose: the compose stack is for a deployment somebody
reaches over the network, and the suite needs a container it starts and throws away per run.
Both read their tag from one place — see "One pin per image" below. Either way the point is
that the HTTP semantics coverage runs against something pinned and deterministic rather than
against httpbin.org, which rate-limits, leaving the public endpoint as a *comparison* rather
than a dependency: the same assertion against both, where the two disagreeing is the
interesting result.

The rest of the Android device matrix is still to come. One of OkHttp's `Remote` tests is
waiting on it and could not move: `AndroidNetworksTest`, which pins a call to a
`ConnectivityManager` network — that tests an Android platform API, not something a JVM can
stand in for.

Public API only
---------------

The testbed drives OkHttp exclusively through its published public API — `OkHttpClient`,
`Request`, `Dispatcher`, `HttpUrl` and friends. Nothing here uses `okhttp3.internal`, and
nothing depends on OkHttp's unpublished test fixtures (`okhttp-testing-support`), so any
released or snapshot version can be dropped in without the tests needing to change. That
is what makes `-PokhttpVersion` meaningful: the results compare versions, not builds.

Two things enforce that, rather than leaving it to good intentions:

- Suites live under `okhttp.testbed.*`, outside OkHttp's own `okhttp3` package, so no test
  can quietly lean on package-level access.
- `checkPublicApiOnly` fails the build on any import of `okhttp3.internal`,
  `okhttp3.testing`, `mockwebserver3.internal` or `okio.internal`. It runs as part of
  `check`, before every `test` task, and before the Android suite's `connected…AndroidTest`
  task, which `check` doesn't cover. Extend `forbiddenImports` in the root
  `build.gradle.kts` as new dependencies arrive.

Chains that must be rejected
----------------------------

`BadChainTest` points OkHttp at the five listeners `test-server` mints for the purpose —
expired, wrong host, self-signed, untrusted root, and a chain missing its intermediate — and
asserts that each handshake fails. It gates, and it should: nothing third-party is in the loop,
the image is built from this repository, and OkHttp accepting any of these would be a defect in
the published artifact rather than a fact about somebody's server.

Two things keep it honest. It trusts the fixture CA properly, through `okhttp-tls`, rather than
installing a permissive trust manager — verification is the thing under test, so weakening it
would empty the suite. And it asserts a *positive control* first: the same client, trusting the
same CA, completing a handshake against the good listener. Without that, a fixture that had
broken in some general way would make every rejection pass for the wrong reason.

The assertion is `SSLException`, not a specific subclass. Which one a client reports for a bad
chain is the client's business; pinning it would turn a change in OkHttp's error reporting into
a failure about certificate validation.

Responses that are wrong on purpose
-----------------------------------

httpbin covers what a well-behaved server does; the failures that reach users come from the
other kind. `test-server` produces those by hijacking the connection and writing bytes no HTTP
library would emit, and two suites read them, split by what kind of answer they give.

`HostileResponseTest` **gates**. It asserts that a response which is malformed beyond argument —
a reset mid-body, a body shorter than its `Content-Length`, chunks with no terminator, a status
line that is not one — fails rather than returning a body as if nothing were wrong. Silently
accepting a truncated body is the failure mode that costs users data, and it is invisible against
a well-behaved server. Its positive control is `/hostile/half-close`, which writes a *complete*
response and only then shuts its write half: that one must succeed, or the others prove nothing
more than that the container is unreachable.

Four endpoints are deliberately left out of it — `duplicate-content-length`,
`content-length-and-chunked`, `huge-header` and `informational-storm`. Those are ambiguous rather
than broken: the RFCs leave a client latitude, so asserting either outcome would be asserting a
preference.

`HostileRetryTest` **reports**, and asks the question that matters more — not whether the call
fails but whether it is *retried*. See the note on it below.

`PublicHostileTest`, in the `network` suite, asks both of testserver.host. It is not a duplicate:
the local `/hostile/reset` sends a response head and part of a body before the RST, where
testserver.host's `/error/reset` and `/error/close` fail with **no response at all**. That is the
case where a client cannot know whether the server processed the request, and so the case where
retrying is both plausible and dangerous. Agreement between the two is the expected result;
disagreement is a finding about one of the servers, which is the reason to run both.

Both use the plain port. The hostile endpoints work by hijacking the connection, which is only
possible under HTTP/1.1; the TLS listener offers h2, where the server answers `501` with an
explanation instead. Testing them over TLS would assert something about ALPN rather than about
malformed responses.

Where a test needs something the public API doesn't offer, prefer solving it with the
container instead of reaching into OkHttp — for example `BasicMockServerTest.trustMockServer()`
builds a real trust manager from MockServer's own keystore rather than disabling
verification. Where that isn't possible, copy rather than depend: the `network` suites
carry their own `DelegatingSSLSocketFactory`, copied from `okhttp-testing-support`, because
that artifact is never published and a dependency on it would tie the suites to a build.

One pin per image
-----------------

`go-httpbin` is run by two things that can't read each other's configuration: the `containers`
suite, through Testcontainers, and `test-server`'s compose stack, for a deployment. Two pins of
one image are two pins that drift, so the tag lives in `gradle/libs.versions.toml` as
`gohttpbin`, the build injects it into the tests as `gohttpbin.version`, and `checkImagePins`
fails if the compose file disagrees:

```
ghcr.io/mccutchen/go-httpbin is 2.16.1 in docker-compose.yml, but 2.25.0 in libs.versions.toml
```

It runs before every `test` task, next to `checkPublicApiOnly`, and for the same reason: a
convention nobody enforces is a convention right up until the first time it matters. Add an
image to `pinnedImages` in the root `build.gradle.kts` when a third one needs pinning in both
places.

One wrinkle worth recording, since it will catch somebody: **the go-httpbin image tag has no
`v`, but it used to.** Releases through 2.21 were published both ways, later ones only without
— so `v2.16.1` and `2.16.1` both resolve, while `v2.25.0` does not exist at all.

Running locally
---------------

Requires Docker and JDK 21+ to run the build. `-PtestJavaVersion` picks the JDK the suites
are *run* on, which is a separate thing and is how CI covers more than one:

```
./gradlew containers:test -PtestJavaVersion=8
```

The two are separate because they have to be. OkHttp supports Java 8 and so does everything
on the test classpath, but Gradle 9 needs 17 to run at all — so "run the suite on 8" cannot
mean "build the whole thing on 8". The root build compiles with the newer JDK and targets the
older one (`release`, `jvmTarget`, and `-Xjdk-release` so an API that doesn't exist on the
target fails at compile time rather than as a `NoSuchMethodError` on the runner), then points
the test task's `javaLauncher` at the JDK under test. Compiling for Java 8 says the bytecode
would load there; only running on Java 8 says OkHttp works there.

The JDK has to be installed and visible to Gradle's toolchain detection. In CI `setup-java`
installs it and the workflow names it with `org.gradle.java.installations.fromEnv`; locally,
`-Porg.gradle.java.installations.paths=/path/to/jdk8` will do.

One suite can't come along: **MockServer's client is Java 17 bytecode**, the only thing on
the classpath that isn't Java 8. Below 17 the suites that use it — `BasicMockServerTest`,
`BasicProxyTest`, `SocksProxyTest` — are excluded from `test`, because JUnit resolves the
classes it found before running any of them and one unloadable class fails the whole task
rather than itself. The suites that run against this repository's own containers are
unaffected, which is most of the module.

```
./gradlew containers:test containers:loomTest containers:hostileTest
./gradlew network:networkTest network:echTest
./gradlew network:echConscryptTest   # after conscrypt/fetch-conscrypt.sh; see ECH on the JVM
```

`test` covers the gating suites and fails the build. `loomTest`, `hostileTest`, `networkTest`,
`echTest` and `echConscryptTest` all run with `ignoreFailures`, because what they report is not this repository being broken —
see [Suites that report rather than gate](#suites-that-report-rather-than-gate). `network`
has no gating task at all: its `test` task is disabled, so those two are the only way to run
it.

The `containers` suites need Docker; the `network` suites need unrestricted outbound
HTTPS, including to DNS-over-HTTPS at `1.1.1.1`. A network that intercepts TLS will fail
them for its own reasons — these tests assert on what the peer saw in the handshake. The
preflight below catches the blunter version of that, where the endpoint isn't reachable at
all, and skips rather than fails.

By default this tests the OkHttp version pinned in `gradle/libs.versions.toml`. To test
another release, a release candidate, or a snapshot:

```
./gradlew containers:test -PokhttpVersion=5.5.0-SNAPSHOT
```

Snapshots resolve from Sonatype; releases from Maven Central.

Endpoint preflight
------------------

A public test server that has gone away should read as *unavailable*, not as OkHttp failing.
Every suite in `network` therefore declares what it depends on, and the dependency is probed
before the assertions run:

```kotlin
@RequiresEndpoint(Endpoint.CLOUDFLARE_SNI, Endpoint.CLOUDFLARE_DNS)
class SniOverrideTest {
```

If the probe fails the test is **skipped**, with the reason attached, rather than failed. If
the probe succeeds the test runs and its failures are recorded exactly as before — a server
that answers and then disagrees with OkHttp is the result this repository exists to catch,
and the preflight must never swallow it. That distinction is the whole design: the probe asks
only "is it there", never anything a test asserts.

`Endpoint` names one server rather than one URL. `tls-ech.dev` publishes four hostnames that
four tests use, but they are one machine — splitting them would report the same outage four
times and probe it four times. Probes run on a stock `OkHttpClient` with short timeouts: the
suites configure OkHttp in ways that are the subject of the test, and a probe carrying the
same configuration could not tell "the server is gone" from "the thing under test is broken".

Each probe runs once per JVM and the results are written to
`network/build/test-results/endpoints-<task>.json`, which the workflow uploads and
`collect_results.py` turns into the endpoint availability table on the status page. "Last
reachable" is read back out of the published history, so "the DNS suite is amber" can be read
against "Quad9 has been unreachable for three days".

Adding a suite means adding its server to `Endpoint` and annotating the class. Declare what
the test *depends on*, not everything it touches.

The handshake OkHttp offers
---------------------------

Every other suite asserts what OkHttp *accepts*. `ClientHelloTest` records what it **sends** —
the thing CDNs and bot-detection systems key on. A ClientHello that shifts between OkHttp
releases can change how Cloudflare or Akamai treat every application using it, and "the API
started returning 403 after we upgraded" is how users find that out today.

How's My SSL answers with the suites, groups and signature algorithms it was offered. The reply
is stored **verbatim** in `network/build/test-results/clienthello-<task>.json`, uploaded with the
JUnit XML, and rendered on the status page next to the OkHttp and Java versions that produced it.
Storing it unedited is deliberate: reformatting would be the one reliable way to lose the field
nobody thought to extract.

Almost none of it is asserted. The offered suite list is the platform's decision far more than
OkHttp's, so pinning it would turn every JDK update into a failed test rather than the
observation it should be. Two things are asserted, and they are the two the issue names: the
negotiated version is at least 1.2, and the rating is not "Bad". `Improvable` — what a client
still offering CBC suites for compatibility gets — is recorded, not failed.

It calls the service once per scheduled run. The `network` workflow does not run on pull
requests, which is what makes that true rather than aspirational: How's My SSL asks to be used
only for clients you control, and a daily request is that.

What GREASE costs, and the extensions that carry it
---------------------------------------------------

A client that supports ECH is meant to send the `encrypted_client_hello` extension even when it
has **no** configuration for the name, so that a handshake using ECH and one not using it look
alike on the wire. The failure that reaches users is not ECH breaking: it is a middlebox
objecting to the extension and breaking a handshake from a client that was never trying to use
ECH in the first place.

`EchGreaseTest` asks the unglamorous half of that. An ordinary `OkHttpClient` — no ECH
configuration, no DoH, nothing arranged — fetches from each public server that speaks ECH, and
each has to serve it. It runs on every platform rather than only where ECH works, which is the
point: the JVM cannot do ECH today and that must not stop it talking to servers that can. Each
case also requires the server to *say* ECH was not used — Cloudflare's `sni=plaintext`, DEfO's
`SSL_ECH_STATUS: not attempted`, `tls-ech.dev`'s "You are not using ECH" — because a success
where ECH had quietly started working would pass while testing something else entirely.

The local half is the offer itself. `test-server`'s `/tls` now reports the ClientHello's
extension IDs in order, which is most of what a JA3 or JA4 fingerprint is built from, with
GREASE values (RFC 8701) named rather than left as mystery hex, and `0xfe0d` called out on its
own. `ClientHelloExtensionsTest` asserts the fixture's own consistency — the list is recorded,
it carries the two extensions no TLS 1.3 handshake can omit, and the ECH flag agrees with the
list it came from — and records what OkHttp offered without asserting it. Today's JVM offers no
ECH extension at all; pinning that would turn the feature arriving into a failure.

`server_name` is not among the required ones, though every ClientHello on the internet carries
one. The fixture is reached as `localhost`, and the JDK omits SNI for a name with no dot in it —
so requiring it would assert a fact about the container's address rather than about OkHttp. It
is in the record either way, which is the distinction this whole section runs on.

What the TLS policy checks actually check
-----------------------------------------

Three things users assume are happening, and only one of them is OkHttp's to promise.
`CertificatePinner` is OkHttp's own, so it is asserted: `PinningTest` sends a deliberately wrong
pin at `pinning-test.badssl.com` and requires both the refusal *and* a message listing the peer's
real pins — the pin a caller needs is exactly the one they got wrong, and an exception without it
sends them to a search engine rather than to the fix. The positive case is against the fixture, in
`FixturePinningTest`, because pinning a live public chain means pinning something that rotates and
a suite that goes red when Let's Encrypt renews is one everybody learns to ignore.

Revocation and Certificate Transparency are **recorded, not asserted**. The JVM does not check
revocation unless `com.sun.net.ssl.checkRevocation` is set and the PKIX parameters say how;
Android varies by release; OkHttp enforces no SCTs at all. A suite insisting
`revoked.badssl.com` "must" be refused would be reporting the platform's documented behaviour as
a defect. The CT probe uses the controlled fixture's privately issued chain, which has no SCT, so
the result is not confounded by a public test certificate expiring or rotating. The answers from
the public and fixture suites are merged by platform into `tlspolicy-<task>.json` records and onto
the status page as the three stable columns Revocation, Pinning and Certificate Transparency, one
row per platform, in neutral colours — the value is the day a row changes.

A timeout is not an answer, and skips. That distinction cost a red run before it was written down:
badssl.com timed out mid-suite and a recording test failed, which put an outage in a column meant
for policy.

`ConnectionSpecTest` is the other half, against `test-server`'s port-per-version listeners. It
asserts what `RESTRICTED_TLS` reaches and what it does not, and that a spec with an empty
intersection fails with something a caller can read rather than a bare connection reset. What it
deliberately does not assert is *who* refuses TLS 1.0: modern JDKs disable it through
`jdk.tls.disabledAlgorithms` before any spec is consulted, so the alert comes back from the
server rejecting an offer that was too new — measured, and true even of `COMPATIBLE_TLS`, which
permits the old versions.

What OkHttp puts on the wire
----------------------------

`test-server`'s raw listener is not an HTTP server: it echoes the request head back byte for byte.
That matters because `/anything` reports what Go *parsed* — `net/http` canonicalises header names
and keeps no record of their order, and both are half of how a CDN fingerprints a client. It is
the only place the difference is visible, and `HttpSemanticsTest.theRequestHeadIsRecorded` is
where it is read.

Almost nothing about it is asserted, for the same reason `ClientHelloTest` asserts almost nothing:
the header set is a platform and version decision, and pinning it would turn an OkHttp upgrade
into a failed test. What is asserted is that the request is well-formed and carries
`Accept-Encoding: gzip`, which OkHttp adds on the caller's behalf and transparent decompression
depends on.

The HTTPS record parameters nobody publishes
--------------------------------------------

`HttpsRecordTest` asks the public names what their `HTTPS` records carry, and they all carry the
same two things: `alpn` and the address hints. RFC 9460 defines rather more, and the rest is close
to unobtainable in the wild — which is exactly why a client's handling of it goes untested.

So the ECH fixture's DoH resolver now publishes them, one name per parameter, each differing from
an ordinary record in a single way, and `SvcParamTest` asks OkHttp what it makes of them:

| Name | What it publishes | What OkHttp does |
|---|---|---|
| `nodefaultalpn.svcb.test` | `alpn=h2` with `no-default-alpn` | reports `[h2]` — the implied `http/1.1` is suppressed |
| `mandatory.svcb.test` | `mandatory=alpn` | uses the record rather than discarding it |
| `unknownparam.svcb.test` | an unregistered key alongside `alpn` | ignores what it does not recognise |
| `alias.svcb.test` | AliasMode, priority 0 | surfaces no metadata; the name still resolves via its A record |

The last row is a finding rather than a promise: OkHttp does not follow AliasMode, and following
one is arguably a resolver's job. What the suite asserts there is the half that *is* a promise —
an AliasMode record must not break ordinary resolution.

The resolver is the one the Android ECH suite runs, started here in `doh` mode alone with a
certificate this test mints and hands over in the environment. It needs `Dns.Record`, so it is
left out of the source set below OkHttp 5.5.0 — the same gate the network module uses, now in the
container module too.

Client certificates
-------------------

`ClientCertificateTest` runs against a listener that *requires* one. That distinction is the whole
suite: `test-server`'s other TLS listeners request a certificate and serve a client that has none,
so "presented" and "ignored" are indistinguishable there. The `mtls` listener verifies against the
fixture CA and `/client.pem` serves an identity it signed — fetched at run time, because the CA is
minted per container and anything committed here could not have been signed by it.

The case worth knowing about is the last one. A certificate from a CA the server does not accept
behaves *exactly* like having none: the server advertises which issuers it will take, the JDK's key
manager finds no match among its identities, and sends nothing. So the server reports a missing
certificate rather than an untrusted one, and the client's own logs agree with it. Anyone debugging
"I configured a certificate and the server says I did not" is meeting this, and the test asserts
the sameness rather than wishing it were otherwise.

`PublicClientCertificateTest` is the reality check against `client.badssl.com`, which requests
rather than requires and answers `400` when nothing arrives — worth knowing, because it means a
missing client certificate can reach an application as an HTTP status rather than as a TLS error.
It loads badssl's published PKCS#12 through a `KeyStore` and a `KeyManagerFactory` rather than
`okhttp-tls`: `HandshakeCertificates` has no route in from a PKCS#12, and converting it first
would mean testing the conversion.

One automatic re-run, for the emulator and nothing else
-------------------------------------------------------

`android-ech` fails on infrastructure often enough to be a nuisance: the emulator comes up
half-dead, the APK install answers `Can't find service: package`, and the job finishes having run
zero tests. That is not a result about OkHttp, and re-running it by hand was the only response.
`rerun-flaky.yml` does it automatically now.

It is deliberately narrow, because an auto-retry is a way to hide real failures. Only
`android-ech` — the container and network suites fail for reasons worth reading, and extending
this to them would be claiming their failures are noise too. Only once, guarded on
`run_attempt == 1`: something that fails twice is either broken or flaky enough to fix rather than
absorb. And the retry is visible as attempt 2 of the same run, so the history shows both.

If it starts firing regularly, that is the signal to fix the emulator setup rather than to widen
the retry.

The resolver matrix
-------------------

`okhttp-dnsoverhttps` is tested upstream against recorded responses. What that cannot cover is
live resolvers *disagreeing*, and they do: Quad9 and AdGuard filter, by design and by different
rules, so a name that answers at Cloudflare and not at Quad9 is a DNS policy result rather than a
client bug. `DohMatrixTest` asks all four the same handful of names and writes down what came
back, in `network/build/test-results/doh-matrix-<task>.json`, uploaded and rendered like the
ClientHello record.

Publishing the difference is the point, so almost nothing is asserted. Two names are: a control
every resolver must resolve, and an RFC 2606 `.invalid` name none of them may. The rest — an ad
domain, a name published to be classified as malicious, a name carrying an `HTTPS` record — are
recorded, because failing a resolver for exercising its own policy would be publishing a
preference as a defect.

One outcome has its own name. A filtering resolver can *withhold* an answer, or it can answer
`0.0.0.0`; the second arrives at a caller as a successful lookup that then fails to connect, which
is much quieter than a resolution error, so it is recorded as `sinkholed` rather than `resolved`.
A resolver the preflight found unreachable is `unavailable` and is never mistaken for one that
said no.

Three more suites hang off the same machinery. `HttpsRecordTest` asks what an `HTTPS` record's
parameters *mean* rather than whether they arrived — most usefully that RFC 9460 §7.1.1 implies
the default ALPN, so a record saying `alpn=h3,h2` means three protocols and not two, and that an
absent `port` means 443 rather than 0. `DnsFailureTest` asks what a resolution failure looks like
to a caller: SERVFAIL and NXDOMAIN have to be told apart, and a resolver answering `429` — which is
nothing to do with the name — has to be tellable from a name that does not exist.
`HappyEyeballsTest` puts an unreachable address first and requires the connection to happen anyway.

Two of those found something worth keeping. The first is the answer to that last question, and it
is not the obvious one: `Dns.lookup` declares `UnknownHostException` and nothing else, so a
resolver answering `429` arrives as exactly that, with a message that is the bare hostname and the
`IOException("response: 429 …")` only as its cause. The type cannot be caught for, which is the
platform's convention rather than an OkHttp quirk — `InetAddress.getAllByName` flattens every
`getaddrinfo` error, retryable and not, into the same exception, and Android's `DnsResolver` is the
one mainstream API that doesn't, by not being `InetAddress`-shaped at all. So the matrix's `errored`
outcome — for resolvers that turn a DNSSEC validation failure into an HTTP `502` — is read off the
cause chain rather than off a distinct exception. The second: every client in the Happy Eyeballs
suite sets `Proxy.NO_PROXY`, because with a proxy in the way OkHttp connects to the proxy and the
pinned addresses are never dialled: the suite would pass having tested nothing.

`DohServiceMetadataTest` asks the other half: what an `HTTPS` record carries, through `newCall`
and `Dns.Record.ServiceMetadata`, since an ECH config list has nowhere else to come from. That
API arrived in 5.5.0, so the suite is left out of the source set below it — the same version gate
`EchTest` uses, without the Conscrypt half, because no TLS stack is involved in what DNS said.

The ECH suite
-------------

`android-ech` tests Encrypted Client Hello end to end. It needs Docker *and* an emulator,
which is why it is its own suite with its own workflow rather than another entry under
`containers`.

Two containers stand behind it, built from one small Go program in `ech-fixture`:

- an origin that holds the ECH keys, generates the CA and leaf certificates, and answers
  every request with the two facts the test is about — whether the handshake it accepted
  used ECH, and which name it was for;
- a DoH resolver, configured from the origin's keys, answering HTTPS records that carry an
  ECH config list, the origin's port, and an IPv4 hint.

Three hostnames give three outcomes. `green.secret.test` is published with the config the
origin holds, so the first handshake is accepted. `retry.secret.test` is published with a
stale config and the origin offers a retry config, so the client should retry and succeed
with ECH. `disabled.secret.test` is published with a stale config and the origin offers
nothing, so the client should fall back to a handshake without ECH rather than fail.

The tests run on the device, and the device has no Docker — so the containers run on the
host and the device reaches them over `adb reverse`. `ech-fixture` is what starts them:
not a test, but a process that publishes its host ports and the fixture CA to a file and
stays up until that file is deleted. `run-ech-test.sh` ties the two together:

```
android-ech/run-ech-test.sh              # fixture, adb reverse, instrumentation tests
android-ech/run-ech-test.sh --smoke-only # fixture only, for a machine with no emulator
```

It needs a running emulator or a connected device, and what it can assert depends on which:

| API level | What the run establishes                                                        |
|-----------|---------------------------------------------------------------------------------|
| 21–28     | The library loads and initializes. Every case skips: the fixture origin is TLS 1.3 only, and Android gained TLS 1.3 in API 29 |
| 29–36     | The fallback. A config list OkHttp cannot apply must produce an ordinary handshake to the real name rather than a failed call |
| 37+       | ECH itself — `android.net.ssl.EchConfigList`, which is how OkHttp's Android platform applies a config list, arrived there |

The cases also skip on a run that didn't come through the script, which is what supplies the
fixture's ports and CA.

This suite tests **5.5.0-SNAPSHOT** by default, not the release the other suites pin, and
`libs.versions.toml` carries that as a separate `ech-okhttp` version. It has to: the suite
needs `DnsOverHttps.Builder.includeServiceMetadata`, and no release has it — 5.4.0 resolves
A and AAAA records only, so there is no HTTPS record to carry an ECH config list. Point it
at whatever you like with `-PokhttpVersion`, and drop `ech-okhttp` once a release ships the
API.

`android-ech` also runs `PublicEncryptedClientHelloTest`, which is `EchTest`'s cases against
the same public servers the JVM suite calls — `tls-ech.dev`, `defo.ie`, `cloudflare-ech.com`.
It is there so the public-server results can be read across platforms: the JVM row of the
status page and the Android row are then the same assertions against the same servers, and
the only variable left between them is the TLS stack. It runs first, and its failures do not
fail the job, for the same reason nothing in `network` gates — those servers belong to other
people. Before it runs, the script waits for the emulator itself to reach `1.1.1.1`; if the
device never acquires an outbound route, the public cases skip rather than all reporting the
same infrastructure failure. The fixture suite that follows it does gate.

ECH is Android-only in OkHttp today: JVM platforms accept the config list and ignore it. The
`network` suite is where that shows up, from the other direction — `EchTest` came from
OkHttp's `android-test` with its assertions intact, so on the JVM the route assertions pass,
the assertions about what the server saw fail, and the difference is recorded rather than
fixed up. See [Suites that report rather than gate](#suites-that-report-rather-than-gate).
The ECH suites are not duplicates: `android-ech` proves the client behaviour against a
fixture nobody else can change, `network` is what notices when `tls-ech.dev` or `defo.ie`
does change, and `echConscryptTest` below is what says *why* the JVM ones are red.

ECH on the JVM
--------------

`network:echTest` cannot pass on the JVM, and it is worth being precise about what is
missing, because it is less than it looks.

There is no published TLS stack a JVM can load that will encrypt a client hello. Conscrypt's
`google3-export` branch has one — `Conscrypt.setEchConfigList(SSLSocket, byte[])` is public
API there and in no release. `conscrypt/` builds that branch and caches the result as a
release on this repository, and `network:echConscryptTest` runs the ECH cases against it:

```
conscrypt/fetch-conscrypt.sh
./gradlew network:echConscryptTest -PokhttpVersion=5.5.0-SNAPSHOT
```

Two suites run under that task. `EchClientHelloTest` reads the bytes of the client hello
against a local socket that accepts a connection and says nothing — no DNS, no internet, no
server — and asserts that the name is not in them. `EchConscryptTest` is `EchTest`'s cases
against the public servers, with the two things the JVM lacks supplied from outside OkHttp:
this Conscrypt, and a network security policy saying ECH is allowed. When those pass and
`echTest` doesn't, the difference between them is one call OkHttp's `ConscryptPlatform`
doesn't make. It is not a claim that OkHttp does ECH on the JVM — the suite makes that call
itself, from a socket factory, precisely because OkHttp doesn't.

The whole arrangement is temporary and `conscrypt/` should be deleted the day Conscrypt ships
ECH. [`conscrypt/README.md`](conscrypt/README.md) has the detail: what is missing where, why
the build is cached as a release rather than run per commit, and why the stale-config retry
case has no counterpart on the JVM at all.

That suite is also the one place a version matters to compilation. `Route.echConfigList` and
`DnsOverHttps.Builder.includeServiceMetadata` arrived after 5.4.0, so
`network/build.gradle.kts` leaves `EchTest.kt` out of the source set and disables `echTest`
below 5.5.0 rather than failing to build. Every other suite compiles against any version —
that is the rule, and this is the documented exception to it.

CI
--

The `containers` workflow runs on push, on pull requests, and daily. The daily run is the
point: it catches breakage that arrives from outside this repository — a container image
that moved, a proxy that changed behaviour, a test server that stopped offering ECH, a
regression in a published OkHttp.

The `network` workflow **does not run its tests per commit**, and that is deliberate. Those
suites are guests on servers other people pay for; tying them to commits would mean a busy
afternoon costs those servers dozens of runs to learn what the daily run already establishes
once. They run on the schedule, and on demand:

| Job                              | Version                              | Runs on                       |
|----------------------------------|--------------------------------------|-------------------------------|
| `containers (pinned release, JDK 21)` | the `okhttp` version in `libs.versions.toml` | every event       |
| `containers (…, JDK 8 · 11 · 17 · 21 · 25)` | the snapshot on 17 and up, the pinned release on all five | schedule and manual runs only |
| `network / compile`              | the `okhttp` version in `libs.versions.toml` | push and pull request  |
| `network (…, JDK 8 · 11 · 17 · 21 · 25)` | the snapshot on 17 and up, the pinned release on all five | schedule and manual runs only |
| `android-ech (…, API 21 · 30 · 35 · 37)` | the snapshot                 | API 37 on every event, the rest on schedule and manual runs only |

What keeps the network module honest between scheduled runs is `network / compile`, which
runs on every push and pull request touching `network/**` and calls nobody: it compiles the
suites and runs `checkPublicApiOnly`, so a suite that stops building, or that reaches into
`okhttp3.internal`, is caught on the commit that did it rather than the next morning.

Container push and pull request runs test one version, because those runs are about this
repository. The snapshot job is what makes the daily schedule worth having: a regression in
OkHttp's main branch shows up here before it reaches a release, which is only possible
because the suites use the public API and so need no changes to run against an unreleased
build. A `workflow_dispatch` run with an explicit `okhttpVersion` overrides the matrix and
tests only that version — which is also how to get a network answer without waiting for
tomorrow.

### Which versions the daily run covers

Per-commit runs test one JDK and one emulator, because those runs are about this repository.
The daily run is where the version axes open up, since the question they answer — how a
*published* OkHttp behaves across the platforms its users are on — doesn't change with a
commit here and isn't worth asking more than once a day:

| Axis        | Daily coverage | Why those                                                    |
|-------------|----------------|--------------------------------------------------------------|
| JDK         | 8, 11, 17, 21, 25 | 8 is the floor, because it is OkHttp's: JUnit 5, Testcontainers, assertk and OkHttp are all Java 8 bytecode, and the toolchain split above is what stops Gradle's own need for 17 setting the floor instead. 25 is the current LTS and the ceiling. 11 and 17 are the LTS releases applications are still on. 21 earns its place twice over — it is the LTS most builds are on, and the one the Loom finding is about: `BasicLoomTest` is `@EnabledForJreRange(min = JAVA_21)`, and JEP 491 changes its answer on 24+, so the 21 and 25 rows are the before and after of that. Java 26 is out and would work — Kotlin 2.4 targets it — but the LTS ceiling is the one users are on. 8 and 11 test the pinned release only: what they are asked is whether the artifact people can depend on today still works where they are |
| Android API | 21, 29, 34, 37 | 21 is the module's `minSdk` and OkHttp 5's. 29 is the first level with TLS 1.3, and so the first that can reach the fixture at all. 34 is where most devices in the field are. 37 is where ECH exists. What each level actually establishes is in the table under [The ECH suite](#the-ech-suite) |

Four scheduled workflows, spread across the day rather than started together — `containers`
at 02:17 UTC, `test-server` at 06:41, `network` at 10:43, `android-ech` at 14:47. Each is
now several jobs wide, and a failure is easier to read against a quiet runner queue than
against three other suites' worth of containers and emulators. It also keeps the network
suites' load on other people's servers spread out rather than arriving in one burst.

The `job-reruns` workflow maintains a Renovate-style **Job re-runs** issue for those four
workflows. Its table links to the latest scheduled or manual run and shows the result. Checking
one or more items under **Run now** dispatches exactly those workflows on `main`; the workflow
clears the accepted boxes immediately, and refreshes the table whenever each dispatched run
completes. The issue and its `job-reruns` label are created automatically when the workflow
first lands on `main`, and `workflow_dispatch` can recreate or refresh them later.

Widening the matrices is what makes the status page's suite rows carry the platform they ran
on: a version card merges every artifact testing that version, so `GoHttpbinTest · JDK 17`
and `GoHttpbinTest · JDK 25` are separate rows. The variant comes from `run-metadata.json`
rather than from the artifact name, for the same reason the version does.

When OkHttp's main branch moves past 5.5.0, bump the snapshot version in the workflow's
matrix. Snapshot runs re-resolve the artifact every time — each suite's `build.gradle.kts`
sets `cacheChangingModulesFor(0, "seconds")` for `-SNAPSHOT` versions, since Gradle
otherwise caches a changing module for 24 hours and a daily job would test yesterday's
build under today's name.

JUnit XML results are uploaded as artifacts on every run — from the gating and the reporting
tasks alike, one per version per JDK, as `container-test-results-<version>-jdk<version>` and
`network-test-results-<version>-jdk<version>`, alongside a `run-metadata.json` recording which OkHttp
version `pinned` actually resolved to — which is what the status page is built from. Each job
runs with `--continue` so one failing suite doesn't rob the others of a result, and the
matrix runs with `fail-fast: false` so one failing version doesn't rob the other.

The `network` job's colour says nothing about its results, because neither of its tasks
gates: a red `network` job means the build itself broke, not that a test failed. The results
are in the XML, and on the status page.

Its artifact carries one more file than the others: `endpoints-<task>.json`, written by the
reachability preflight rather than by JUnit. Because the network workflow's per-commit job
uploads nothing, `pages.yml` collects the most recent run *carrying artifacts* rather than
simply the most recent run — otherwise a compile-only run would blank the network results.

The `android-ech` workflow runs on the same events, on its own daily schedule, and uploads
its results as `android-ech-test-results-<version>-api<level>`. It runs one OkHttp version
rather than a matrix of them — the snapshot — because that is the only version with the API
the suite needs; its matrix is over emulators instead. Every job boots one, so it is slower
and more failure-prone than the container jobs; that is the price of testing ECH at all, and
it is why it is a separate workflow whose colour doesn't mask the container suites'. Push and
pull request runs boot only the API 37 emulator, which is the one that can do ECH.

Both workflows write a `run-metadata.json` into their artifact recording which OkHttp
version they actually resolved, and which run produced it. That is what lets the status site
name the version under test rather than calling it "pinned", and report two workflows'
results as one picture.

Status site
-----------

<https://yschimke.github.io/okhttp-testbed/> — the most recent results per OkHttp version,
per suite, across both workflows, with the failing assertions in full, a history strip, and
a topic page per area covered (ECH, DNS, TLS, proxies, virtual threads) linking the relevant
RFCs and the test servers involved, and a [survey of the public test servers][test-servers]
the `network` suite runs against today and the planned ones will grow into.

The site is `site/`, deployed as it sits: plain HTML and CSS, no static site generator, no
build step. The only generated files are `site/data/latest.json` and `site/data/history.json`.

`.github/workflows/pages.yml` runs when a `containers`, `network` or `android-ech` run
finishes on `main`. Whichever triggered it, it collects the most recent artifact-bearing run
of *all three* — so a container run finishing doesn't blank the ECH results, or the reverse —
converts the JUnit XML with `site/tools/collect_results.py`, and deploys. Results are keyed by
OkHttp version, so a suite from each workflow testing `5.5.0-SNAPSHOT` lands on one card.

History is not committed: the workflow fetches `data/history.json` back off the deployed
site, appends the snapshot it just built, and republishes — the deployed site is its own
datastore, capped at the last 120 entries. A pull request's run is deliberately not
published; it is about the pull request, not about the state of the repository.

The page leads with the results — the current status, then what failed, then the suites,
then endpoint availability, then history. The explanation of what any of it means sits under
those, collapsed: this is a page you return to for the state of things, not to read.

It also lists the open issues, grouped by area. That comes from the GitHub API on every build
rather than from an artifact, so it is current even on a build that collected no test results,
and the grouping comes from each issue's `area:` label — `area:http`, `area:tls`, `area:dns`,
`area:ech`, `area:infrastructure` — rather than from a table in the site. **Label a new issue
and it appears under the right heading with nothing else to change.** An issue with no `area:`
label still shows, under "Unfiled", because dropping it would make the section quietly wrong
rather than visibly incomplete; the `tracking` label marks the roadmap issue itself, which is
rendered as a link above the groups rather than as an item in them.

Suite names on it link to the test that produced them. The link is derived from the workflow
and the class name rather than recorded, so a new suite is linked the day it lands; the cost
is that moving a module without updating `SOURCE_ROOTS` in `site/assets/status.js` gives a
404 rather than a missing link.

Failures also link into the relevant topic page, down to the exact suite result where that page
has suite-level evidence. Repeated failures are folded into likely-related groups using a weighted
score: test class 25%, test method 15%, exception type 15%, message-token similarity 20%, and
stack-frame similarity 25%. Every result in a group must score at least 65% against every other
member, which prevents a chain of weak matches from swallowing unrelated failures. The page shows
the score range and the per-result evidence; the pure scorer and its tests live in
`site/assets/finding-groups.js` and `site/assets/finding-groups.test.js`.

Three distinctions the page depends on, all decided when the results are collected:

- The Gradle task a suite ran under decides whether a failure is **failing** or a
  **finding**. `test` failing means this repository is red; `loomTest`, `hostileTest`,
  `networkTest` and `echTest` failing are recorded findings, and the page shows them in amber — see below. A
  suite's task comes
  from the artifact layout for the container and network suites, and from `run-metadata.json` for
  `android-ech`, where the XML is laid out by device rather than by task.
- The version comes from `run-metadata.json`, so the page names `5.4.0` rather than
  "pinned".
- Endpoint availability comes from `endpoints-<task>.json`, not from the XML, and is not tied
  to an OkHttp version — a server is up or down for everyone. Where a probe disagrees between
  tasks, `down` wins: a server that failed anybody's probe is not one to trust a result to.

This needs Pages set to deploy from GitHub Actions — Settings → Pages → Source: GitHub
Actions — which is a one-time setting on the repository, not something the workflow can do
for itself. Until it is set, the workflow's deploy step is the only thing that will fail.

To change the site, edit `site/` and push to `main`; a push touching `site/**` redeploys it
and carries the published results forward. To preview locally, generate some data from a
directory of downloaded artifacts and serve the folder:

```
python3 site/tools/collect_results.py --artifacts <downloaded-artifacts> --out site/data
python3 -m http.server --directory site 8000
```

Suites that report rather than gate
-----------------------------------

Some suites here fail for reasons that are not this repository being broken. Those run under
`ignoreFailures`: the assertion stays exactly as written, the failure lands in the JUnit XML
for the status page, and the build stays green. There are two reasons a suite qualifies.

Not gating is not the same as not mattering, and treating them as the same is how a real
regression hides. The status page separates three things a non-gating failure can be:

| | shown as | means |
|---|---|---|
| **expected** | amber, folded shut | The repository predicted this and can say why. `EchTest`'s `JDK` cases can't pass until a Conscrypt carrying the ECH API is released — that *is* the finding, so it is recorded with its reason and kept quiet. Declared per case in `site/tools/collect_results.py`. |
| **unexpected, critical** | red | A surprise in a suite whose question the repository is currently trying to answer. The ECH suites are critical today; move one back to `watch` when its question is settled. |
| **unexpected, watch** | amber | A surprise in a suite being kept honest, where a server nobody here operates is as likely a cause as the client. |

A skip is none of these: an endpoint the preflight found unreachable reads as unavailable
rather than as a client that broke. And red here is the *page*, never the build — these
suites still report rather than gate.

**It asserts something about OkHttp, or about the platform, that is currently false.** That
is a finding, and the point of recording it.

`BasicLoomTest.testHttpsRequest` asserts that no virtual
thread pins its carrier, and against OkHttp 5.4.0 on JDK 21 one does:

```
VirtualThread[#51]/runnable@ForkJoinPool-1-worker-3 reason:MONITOR
    okhttp3.internal.http2.Http2Writer.flush(Http2Writer.kt:131) <== monitors:1
    okhttp3.internal.http2.Http2Connection.newStream(Http2Connection.kt:270) <== monitors:2
```

`Http2Connection.newStream` holds a monitor across `Http2Writer.flush`'s blocking write.
JEP 491 removes this class of pinning on JDK 24+, so the same test should pass there —
which is exactly the kind of difference this repository exists to record.

`HostileRetryTest` is a third. It counts how many times OkHttp sends a request the server
killed under it, which matters most for a `POST`: one sent twice because the connection dropped
mid-response can charge a card twice, and the caller sees a single `IOException` either way.
Whatever the count turns out to be, it is a fact about OkHttp's retry policy rather than a defect
here, so it is recorded. Its sibling `HostileResponseTest` stays fatal, because "a truncated body
must not read as a complete one" is an invariant rather than a policy.

`EchTest` is the other of that kind. ECH takes two halves: OkHttp reads an ECH config list out of the
DNS HTTPS record, and the TLS stack encrypts the client hello with it. OkHttp's half works
on the JVM — the routes carry a config list, which is what the `echConfigList` assertions
check — but the JDK's TLS stack has no ECH, so the servers report back that the connection
was not protected and the assertions about what they saw fail. Android does have it from
API 37, which is why the same test passes in OkHttp's `android-test`. When a JVM TLS stack
gains ECH, this suite is how we will find out.

**It depends on a server this repository does not operate.** A rate limit at a CDN, a
certificate renewed overnight, a runner behind a captive resolver — none of those is a result
about OkHttp, and none of them should turn this repository red. That covers the whole
`network` module, whose `test` task is disabled so that everything in it runs under
`networkTest` or `echTest`. Disabling the gating task rather than excluding one class from it
is deliberate too: a suite added to `network` later cannot end up gating the build by being
written in the wrong file.

The trade is that a genuine break in a `network` suite — one this repository caused — reports
in amber rather than red. That is the right way round while every test in the module is
reaching out over the internet: the alternative is a red repository most mornings, which
teaches everyone to ignore the colour. Coverage that could gate belongs in `containers`,
against something we run — which is what
[issue #8](https://github.com/yschimke/okhttp-testbed/issues/8) is for.

Everything else stays fatal. That is deliberate: a fatal `test` task is what caught the
MockServer client/server version mismatch on the first run that reached a Docker daemon.
Move a suite into the reporting category for one of the two reasons above, never to quiet a
test that is genuinely broken here.

Reading a failure
-----------------

A red result here does not necessarily mean OkHttp is broken. These tests depend on
third-party container images and on servers other people operate.
Check whether the failure reproduces locally and whether it correlates with a new OkHttp
version before filing anything upstream.

License
-------

Apache 2.0, as OkHttp is. The tests in `containers` and `network` were moved from the
OkHttp repository and keep their original copyright headers.

[okhttp]: https://github.com/square/okhttp
[test-servers]: https://yschimke.github.io/okhttp-testbed/topics/test-servers.html
[roadmap]: https://github.com/yschimke/okhttp-testbed/issues/5
