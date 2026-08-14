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
| `containers`  | Docker                      | SOCKS5 and HTTP proxies, TLS via MockServer, HTTP semantics via go-httpbin, virtual threads (Loom) |
| `network`     | Outbound network            | ALPN and SNI overrides, Let's Encrypt trust, ECH on the public servers |
| `android-ech` | Docker, an API 37 emulator  | Encrypted Client Hello over DoH: accepted, retried, and declined     |

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

`test-server` is what the suites will assert positive results against, and the one endpoint
that reports what a client's handshake actually looked like: a CA it generates itself so a
test can assert a chain *is* accepted, `/tls` reporting the negotiated handshake and the
ClientHello it came from, a port per TLS version the way badssl.com does it, and a set of
responses that are wrong on purpose — resets, truncated bodies, invalid framing. It is a Go
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

Where a test needs something the public API doesn't offer, prefer solving it with the
container instead of reaching into OkHttp — for example `BasicMockServerTest.trustMockServer()`
builds a real trust manager from MockServer's own keystore rather than disabling
verification. Where that isn't possible, copy rather than depend: the `network` suites
carry their own `DelegatingSSLSocketFactory`, copied from `okhttp-testing-support`, because
that artifact is never published and a dependency on it would tie the suites to a build.

Running locally
---------------

Requires Docker and JDK 21+.

```
./gradlew containers:test containers:loomTest
./gradlew network:networkTest network:echTest
```

`test` covers the gating suites and fails the build. `loomTest`, `networkTest` and `echTest`
all run with `ignoreFailures`, because what they report is not this repository being broken —
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

It needs a running emulator or a connected device on API 37 — `android.net.ssl.EchConfigList`,
which is how OkHttp's Android platform applies a config list, arrived there. The tests skip
themselves on anything older, and on a run that didn't come through the script.

This suite tests **5.5.0-SNAPSHOT** by default, not the release the other suites pin, and
`libs.versions.toml` carries that as a separate `ech-okhttp` version. It has to: the suite
needs `DnsOverHttps.Builder.includeServiceMetadata`, and no release has it — 5.4.0 resolves
A and AAAA records only, so there is no HTTPS record to carry an ECH config list. Point it
at whatever you like with `-PokhttpVersion`, and drop `ech-okhttp` once a release ships the
API.

ECH is Android-only in OkHttp today: JVM platforms accept the config list and ignore it. The
`network` suite is where that shows up, from the other direction — `EchTest` came from
OkHttp's `android-test` with its assertions intact, so on the JVM the route assertions pass,
the assertions about what the server saw fail, and the difference is recorded rather than
fixed up. See [Suites that report rather than gate](#suites-that-report-rather-than-gate).
The two ECH suites are not duplicates: `android-ech` proves the client behaviour against a
fixture nobody else can change, and `network` is what notices when `tls-ech.dev` or `defo.ie`
does change.

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
| `containers (pinned release)`    | the `okhttp` version in `libs.versions.toml` | every event            |
| `containers (5.5.0-SNAPSHOT)`    | the current snapshot                 | schedule and manual runs only |
| `network / compile`              | the `okhttp` version in `libs.versions.toml` | push and pull request  |
| `network (pinned release)`       | the `okhttp` version in `libs.versions.toml` | schedule and manual runs only |
| `network (5.5.0-SNAPSHOT)`       | the current snapshot                 | schedule and manual runs only |

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

When OkHttp's main branch moves past 5.5.0, bump the snapshot version in the workflow's
matrix. Snapshot runs re-resolve the artifact every time — each suite's `build.gradle.kts`
sets `cacheChangingModulesFor(0, "seconds")` for `-SNAPSHOT` versions, since Gradle
otherwise caches a changing module for 24 hours and a daily job would test yesterday's
build under today's name.

JUnit XML results are uploaded as artifacts on every run — from the gating and the reporting
tasks alike, for each version, as `container-test-results-<version>` and
`network-test-results-<version>`, alongside a `run-metadata.json` recording which OkHttp
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
its results as `android-ech-test-results-<version>`. It runs one version rather than a
matrix — the snapshot — because that is the only version with the API the suite needs. It
boots an API 37 emulator, so it is slower and more failure-prone than the container jobs;
that is the price of testing ECH at all, and it is why it is a separate workflow whose
colour doesn't mask the container suites'.

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

Three distinctions the page depends on, all decided when the results are collected:

- The Gradle task a suite ran under decides whether a failure is **failing** or a
  **finding**. `test` failing means this repository is red; `loomTest`, `networkTest` and
  `echTest` failing are recorded findings, and the page shows them in amber — see below. A
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
