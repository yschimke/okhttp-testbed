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
| `containers`  | Docker                      | SOCKS5 and HTTP proxies, TLS via MockServer, virtual threads (Loom)  |
| `android-ech` | Docker, an API 37 emulator  | Encrypted Client Hello over DoH: accepted, retried, and declined     |

More suites are planned — notably network tests against external IETF and vendor test
servers, and the rest of the Android device matrix.

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

Where a test needs something the public API doesn't offer, prefer solving it with the
container instead of reaching into OkHttp — for example `BasicMockServerTest.trustMockServer()`
builds a real trust manager from MockServer's own keystore rather than disabling
verification.

Running locally
---------------

Requires Docker and JDK 21+.

```
./gradlew containers:test containers:loomTest
```

`test` covers the container suites and fails the build. `loomTest` runs `BasicLoomTest`
separately with `ignoreFailures`, because that suite reports on OkHttp rather than on this
repository — see [Suites that report rather than gate](#suites-that-report-rather-than-gate).

By default this tests the OkHttp version pinned in `gradle/libs.versions.toml`. To test
another release, a release candidate, or a snapshot:

```
./gradlew containers:test -PokhttpVersion=5.5.0-SNAPSHOT
```

Snapshots resolve from Sonatype; releases from Maven Central.

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

ECH itself is Android-only in OkHttp today: JVM platforms accept the config list and ignore
it, so there is nothing here for the `containers` suite to assert.

CI
--

The `containers` workflow runs on push, on pull requests, and daily. The daily run is the
point: it catches breakage that arrives from outside this repository — a container image
that moved, a proxy that changed behaviour, a regression in a published OkHttp.

The daily run covers two versions, as separate jobs:

| Job                              | Version                              | Runs on                       |
|----------------------------------|--------------------------------------|-------------------------------|
| `containers (pinned release)`    | the `okhttp` version in `libs.versions.toml` | every event            |
| `containers (5.5.0-SNAPSHOT)`    | the current snapshot                 | schedule and manual runs only |

Push and pull request runs test one version, because those runs are about this repository.
The snapshot job is what makes the daily schedule worth having: a regression in OkHttp's
main branch shows up here before it reaches a release, which is only possible because the
suites use the public API and so need no changes to run against an unreleased build.
A `workflow_dispatch` run with an explicit `okhttpVersion` overrides both and tests only
that version.

When OkHttp's main branch moves past 5.5.0, bump the snapshot version in the workflow's
matrix. Snapshot runs re-resolve the artifact every time — `containers/build.gradle.kts`
sets `cacheChangingModulesFor(0, "seconds")` for `-SNAPSHOT` versions, since Gradle
otherwise caches a changing module for 24 hours and a daily job would test yesterday's
build under today's name.

JUnit XML results are uploaded as artifacts on every run — from both `test` and `loomTest`,
for each version, as `container-test-results-<version>`, alongside a `run-metadata.json`
recording which OkHttp version `pinned` actually resolved to — which is what the status page
is built from. The job runs with `--continue` so one failing suite doesn't rob the
others of a result, and the matrix runs with `fail-fast: false` so one failing version
doesn't rob the other.

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
RFCs and the test servers involved.

The site is `site/`, deployed as it sits: plain HTML and CSS, no static site generator, no
build step. The only generated files are `site/data/latest.json` and `site/data/history.json`.

`.github/workflows/pages.yml` runs when a `containers` or `android-ech` run finishes on
`main`. Whichever triggered it, it collects the most recent completed run of *both* — so a
container run finishing doesn't blank the ECH results, or the reverse — converts the JUnit
XML with `site/tools/collect_results.py`, and deploys. Results are keyed by OkHttp version,
so a suite from each workflow testing `5.5.0-SNAPSHOT` lands on one card.

History is not committed: the workflow fetches `data/history.json` back off the deployed
site, appends the snapshot it just built, and republishes — the deployed site is its own
datastore, capped at the last 120 entries. A pull request's run is deliberately not
published; it is about the pull request, not about the state of the repository.

Two distinctions the page depends on, both decided when the XML is collected:

- The Gradle task a suite ran under decides whether a failure is **failing** or a
  **finding**. `test` failing means this repository is red; `loomTest` failing is a recorded
  finding about OkHttp, and the page shows it in amber — see below. A suite's task comes
  from the artifact layout for the container suites, and from `run-metadata.json` for
  `android-ech`, where the XML is laid out by device rather than by task.
- The version comes from `run-metadata.json`, so the page names `5.4.0` rather than
  "pinned".

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

Some tests here assert something about OkHttp that is currently false. That is a finding,
not a broken test, and it shouldn't read as this repository being red — so those suites run
under `ignoreFailures`: the assertion stays exactly as written, the failure lands in the
JUnit XML for the status page, and the build stays green.

Only `loomTest` is in this category today. `BasicLoomTest.testHttpsRequest` asserts that no
virtual thread pins its carrier, and against OkHttp 5.4.0 on JDK 21 one does:

```
VirtualThread[#51]/runnable@ForkJoinPool-1-worker-3 reason:MONITOR
    okhttp3.internal.http2.Http2Writer.flush(Http2Writer.kt:131) <== monitors:1
    okhttp3.internal.http2.Http2Connection.newStream(Http2Connection.kt:270) <== monitors:2
```

`Http2Connection.newStream` holds a monitor across `Http2Writer.flush`'s blocking write.
JEP 491 removes this class of pinning on JDK 24+, so the same test should pass there —
which is exactly the kind of difference this repository exists to record.

Everything else stays fatal. That is deliberate: a fatal `test` task is what caught the
MockServer client/server version mismatch on the first run that reached a Docker daemon.
Move a suite into the reporting category only when it is asserting about OkHttp's
behaviour, never to quiet a test that is genuinely broken here.

Reading a failure
-----------------

A red result here does not necessarily mean OkHttp is broken. These tests depend on
third-party container images and, in future suites, on servers other people operate.
Check whether the failure reproduces locally and whether it correlates with a new OkHttp
version before filing anything upstream.

License
-------

Apache 2.0, as OkHttp is. The tests in `containers` were moved from the OkHttp repository
and keep their original copyright headers.

[okhttp]: https://github.com/square/okhttp
