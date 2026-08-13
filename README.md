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

| Suite        | What it needs      | What it covers                                                    |
|--------------|--------------------|-------------------------------------------------------------------|
| `containers` | Docker             | SOCKS5 and HTTP proxies, TLS via MockServer, virtual threads (Loom) |
| `network`    | Outbound network   | ALPN and SNI overrides, Let's Encrypt trust, ECH                   |

The `network` suites call servers other people operate — Google, Cloudflare, Let's Encrypt,
and the ECH test servers at `tls-ech.dev` and `defo.ie`. They came from OkHttp's
`android-test`, where they were tagged `Remote` and excluded from that build for the same
reason they are here rather than there: they need the internet, and they can fail for
reasons that have nothing to do with a change under review.

The heavier Android device matrix is still to come. Two of OkHttp's `Remote` tests are
waiting on it and could not move: `AndroidNetworksTest`, which pins a call to a
`ConnectivityManager` network, and the `AndroidDns` half of `EchTest` — both test Android
platform APIs, not something a JVM can stand in for.

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
  `check` and before every `test` task. Extend `forbiddenImports` in the root
  `build.gradle.kts` as new dependencies arrive.

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
./gradlew network:test network:echTest
```

`test` covers the gating suites and fails the build. `loomTest` and `echTest` run
`BasicLoomTest` and `EchTest` separately with `ignoreFailures`, because those suites report
on OkHttp rather than on this repository — see
[Suites that report rather than gate](#suites-that-report-rather-than-gate).

The `containers` suites need Docker; the `network` suites need unrestricted outbound
HTTPS, including to DNS-over-HTTPS at `1.1.1.1`. A network that intercepts TLS will fail
them for its own reasons — these tests assert on what the peer saw in the handshake.

By default this tests the OkHttp version pinned in `gradle/libs.versions.toml`. To test
another release, a release candidate, or a snapshot:

```
./gradlew containers:test -PokhttpVersion=5.5.0-SNAPSHOT
```

Snapshots resolve from Sonatype; releases from Maven Central.

One suite is version-sensitive: ECH arrived after 5.4.0, so `Route.echConfigList` and
`DnsOverHttps.Builder.includeServiceMetadata` don't exist in earlier releases and `EchTest`
wouldn't compile against them. `network/build.gradle.kts` leaves that source out of the
build below 5.5.0 and disables `echTest`, which is why `-PokhttpVersion=5.5.0-SNAPSHOT` is
the only way to exercise ECH today. Every other suite compiles against any version — that
is the rule, and this is the one documented exception to it.

CI
--

The `containers` and `network` workflows run on push, on pull requests, and daily. The
daily run is the point: it catches breakage that arrives from outside this repository — a
container image that moved, a proxy that changed behaviour, a test server that stopped
offering ECH, a regression in a published OkHttp.

Each daily run covers two versions, as separate jobs:

| Job                              | Version                              | Runs on                       |
|----------------------------------|--------------------------------------|-------------------------------|
| `containers (pinned release)`    | the `okhttp` version in `libs.versions.toml` | every event            |
| `containers (5.5.0-SNAPSHOT)`    | the current snapshot                 | schedule and manual runs only |
| `network (pinned release)`       | the `okhttp` version in `libs.versions.toml` | every event            |
| `network (5.5.0-SNAPSHOT)`       | the current snapshot                 | schedule and manual runs only |

Push and pull request runs test one version, because those runs are about this repository.
The snapshot job is what makes the daily schedule worth having: a regression in OkHttp's
main branch shows up here before it reaches a release, which is only possible because the
suites use the public API and so need no changes to run against an unreleased build.
A `workflow_dispatch` run with an explicit `okhttpVersion` overrides both and tests only
that version.

When OkHttp's main branch moves past 5.5.0, bump the snapshot version in the workflow's
matrix. Snapshot runs re-resolve the artifact every time — the root `build.gradle.kts`
sets `cacheChangingModulesFor(0, "seconds")` for `-SNAPSHOT` versions, since Gradle
otherwise caches a changing module for 24 hours and a daily job would test yesterday's
build under today's name.

JUnit XML results are uploaded as artifacts on every run — from the gating and the
reporting task alike, for each version, as `container-test-results-<version>` and
`network-test-results-<version>` — which is what the status page will be built from. Each
job runs with `--continue` so one failing suite doesn't rob the others of a result, and the
matrix runs with `fail-fast: false` so one failing version doesn't rob the other.

Suites that report rather than gate
-----------------------------------

Some tests here assert something about OkHttp that is currently false. That is a finding,
not a broken test, and it shouldn't read as this repository being red — so those suites run
under `ignoreFailures`: the assertion stays exactly as written, the failure lands in the
JUnit XML for the status page, and the build stays green.

Two suites are in this category. `BasicLoomTest.testHttpsRequest` asserts that no virtual
thread pins its carrier, and against OkHttp 5.4.0 on JDK 21 one does:

```
VirtualThread[#51]/runnable@ForkJoinPool-1-worker-3 reason:MONITOR
    okhttp3.internal.http2.Http2Writer.flush(Http2Writer.kt:131) <== monitors:1
    okhttp3.internal.http2.Http2Connection.newStream(Http2Connection.kt:270) <== monitors:2
```

`Http2Connection.newStream` holds a monitor across `Http2Writer.flush`'s blocking write.
JEP 491 removes this class of pinning on JDK 24+, so the same test should pass there —
which is exactly the kind of difference this repository exists to record.

`EchTest` is the other. ECH takes two halves: OkHttp reads an ECH config list out of the
DNS HTTPS record, and the TLS stack encrypts the client hello with it. OkHttp's half works
on the JVM — the routes carry a config list, which is what the `echConfigList` assertions
check — but the JDK's TLS stack has no ECH, so the servers report back that the connection
was not protected and the assertions about what they saw fail. Android does have it from
API 37, which is why the same test passes in OkHttp's `android-test`. When a JVM TLS stack
gains ECH, this suite is how we will find out.

Everything else stays fatal. That is deliberate: a fatal `test` task is what caught the
MockServer client/server version mismatch on the first run that reached a Docker daemon.
Move a suite into the reporting category only when it is asserting about OkHttp's
behaviour, never to quiet a test that is genuinely broken here.

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
