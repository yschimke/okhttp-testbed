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

More suites are planned — notably network tests against external IETF and vendor test
servers, and the heavier Android device matrix.

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
verification.

Running locally
---------------

Requires Docker and JDK 21+.

```
./gradlew containers:test
```

By default this tests the OkHttp version pinned in `gradle/libs.versions.toml`. To test
another release, a release candidate, or a snapshot:

```
./gradlew containers:test -PokhttpVersion=5.5.0-SNAPSHOT
```

Snapshots resolve from Sonatype; releases from Maven Central.

CI
--

The `containers` workflow runs on push, on pull requests, and daily. The daily run is the
point: it catches breakage that arrives from outside this repository — a container image
that moved, a proxy that changed behaviour, a regression in a published OkHttp.

JUnit XML results are uploaded as artifacts on every run, which is what the status page
will be built from.

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
