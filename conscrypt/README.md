Conscrypt, for ECH
==================

A build of Conscrypt's OpenJDK artifact from a branch, cached as a release on this repository.
It exists for one reason: **there is no published TLS stack a JVM can load that will encrypt a
client hello**, so `network:echTest` cannot pass on the JVM, and without something like this
there is no way to say whether that is OkHttp's problem or the platform's.

This directory should be deleted the day Conscrypt ships ECH.

What's missing, and where
-------------------------

Encrypted Client Hello takes two halves. OkHttp supplies the first on every platform: it reads
the ECH config list out of the DNS `HTTPS` record and carries it on the [`Route`][route]. The TLS
stack supplies the second, and on the JVM three separate things are in the way.

| What | Where | Status |
|------|-------|--------|
| A TLS stack that can encrypt a client hello | Conscrypt `google3-export` | Exists, unpublished — this directory |
| Handing the config list to that stack | OkHttp `ConscryptPlatform.configureTlsExtensions` | Takes an `echConfigList` and ignores it |
| Reading a server's retry config back | Conscrypt OpenJDK `Platform.wrapEchRejectedException` | Discards the retry configs and the public name |

The first is why this directory exists. The second is the small piece of work
[lysine-dev/okhttp#9559][okhttp-pr] does, one call to `Conscrypt.setEchConfigList` alongside the
ALPN and session-ticket configuration that method already does — `Android10Platform` is the model.

`network:echPlatformTest` measures that second row rather than describing it. `EchConscryptPlatform`
is a `Platform` that makes exactly that call and nothing else new; `EchPlatformTest` then runs
`EchTest`'s requests through ordinary public API with it installed. The two suites are the same
client against the same servers, so the difference between their results is the one call. That
platform is the only file here allowed to import `okhttp3.internal` — it is a `Platform`, which
OkHttp declares nowhere else — and it says so with a `USES-OKHTTP-INTERNALS:` marker that
`checkPublicApiOnly` reports on every run.

The third is a Conscrypt change rather than an OkHttp one, and it is why `network:echConscryptTest`
has no counterpart to `EchTest.echIsRetriedOnStaleTlsEchDev`. On Android, a rejected ECH config
arrives as an `EchConfigMismatchException` carrying the config the server offered instead, which
is what `Android10Platform.getEchRetryConfig` reads. On OpenJDK the same code path throws an
`EchRejectedException` with the retry configs dropped on the floor, and nothing public exposes
`SSL_get0_ech_retry_configs` — so a stale config can be detected and not recovered from, on any
JVM client, however OkHttp is changed.

The ECH work in Conscrypt was proposed as [google/conscrypt#1406][conscrypt-pr], which is still
open; what landed on `google3-export` is Google's internal version of it, exported by Copybara.
The API this suite uses is `Conscrypt.setEchConfigList(SSLSocket, byte[])`.

How it's built and cached
-------------------------

The build takes several minutes, needs a C++ toolchain and two source trees, and its output
depends on nothing in this repository except `pinned.properties`. So it does not happen in a test
workflow. The `conscrypt` workflow builds it when that file changes and publishes the jars as a
release; the release tag carries both pinned shas, so bumping a pin invalidates the cache by
construction, and a run that finds its tag already published does nothing.

| File | What it does |
|------|--------------|
| `pinned.properties` | The Conscrypt and BoringSSL commits. The cache key. |
| `build-conscrypt.sh` | Builds BoringSSL for x86-64 and aarch64, then `conscrypt-openjdk`, into `build/dist`. |
| `fetch-conscrypt.sh` | Downloads the release for the pinned shas into `build/dist`, checksums it. |
| `release-tag.sh` | The tag those two agree on. |

To run the JVM ECH suite locally:

```
conscrypt/fetch-conscrypt.sh          # or --build-if-missing, if no release exists yet
./gradlew network:echConscryptTest -PokhttpVersion=5.5.0-SNAPSHOT
```

`network/build.gradle.kts` picks up `build/dist/conscrypt-openjdk-*.jar` if it is there and leaves
`EchConscryptTest` out of the build if it isn't, so neither the fetch nor the build is on anyone's
critical path.

Building it needs `cmake`, `ninja`, `clang`, a JDK and an aarch64 cross compiler. Conscrypt's own
`jar` task builds a native library per architecture the host can target and fails if any of them
won't link, which is the only reason aarch64 is built at all — nothing here uses it.

What a pass means
-----------------

`network:echConscryptTest` supplies the two things the JVM lacks — this Conscrypt, and a network
security policy saying ECH is allowed, which on Android comes from `network_security_config.xml`
and on the JVM defaults to a value Conscrypt reads as "no" — and leaves the rest to OkHttp. When
it passes and `network:echTest` doesn't, the difference between them is the second row of the
table above and nothing else.

It is not a claim that OkHttp does ECH on the JVM. It cannot be: the suite makes the
`setEchConfigList` call itself, from a socket factory, because OkHttp doesn't. What it does say is
that everything else is in place, and that the remaining change is worth making.

[route]: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-route/
[okhttp-pr]: https://github.com/lysine-dev/okhttp/pull/9559
[conscrypt-pr]: https://github.com/google/conscrypt/pull/1406
