A build of [Conscrypt][conscrypt]'s OpenJDK artifact from the `google3-export` branch, for the
testbed's Encrypted Client Hello suites. **Not a Conscrypt release.** It is published here only
because ECH is not in one yet.

`Conscrypt.setEchConfigList(SSLSocket, byte[])` exists on that branch and in no published
Conscrypt artifact, so the JVM has no TLS stack that can encrypt a client hello. That is what
`network:echTest` reports, and what `network:echConscryptTest` uses this to isolate.

The exact commits, of both Conscrypt and BoringSSL, are in the tag and in `build-info.json`.
Verify the jars against `SHA256SUMS`. Delete this release once Conscrypt ships ECH: at that point
`conscrypt/` should go and the suite should depend on the release instead.

Built by [`conscrypt/build-conscrypt.sh`][script] in the `conscrypt` workflow.

[conscrypt]: https://github.com/google/conscrypt
[script]: https://github.com/yschimke/okhttp-testbed/blob/main/conscrypt/build-conscrypt.sh
