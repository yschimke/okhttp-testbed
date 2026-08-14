test-server
===========

The testbed's own server: a fixture the suites can assert *positive* results against, and
the one endpoint that reports what a client's handshake actually looked like.

It is a single Go program with no dependencies outside the standard library, and it is
deliberately not built on OkHttp. A server sharing its framing and its TLS stack with the
client under test cannot answer whether that client's ClientHello or its HTTP/2 framing are
acceptable to anything else — which is the whole reason this repository exists. Go's
standard library is an independent implementation of both.

It covers three things nothing else here does:

- **A trusted anchor we control.** The server mints its own CA at startup and serves it at
  `/ca.pem`. Asserting that a bad chain is *refused* works against anything; asserting that
  a good one is *accepted* needs a CA nobody else can change.
- **The handshake, reported back.** `/tls` answers with the negotiated version, suite and
  ALPN protocol, and with the offer they were chosen from — the client's supported versions,
  cipher suites, curves and signature schemes, and the IDs of the extensions it sent, in
  order. That is issue #17's local half. The extension list is most of what a JA3 or JA4
  fingerprint is computed from; the extensions' *contents* are parsed away by `crypto/tls`
  and are not reported, so this answers what OkHttp offered rather than exactly how a CDN
  would fingerprint it. GREASE values (RFC 8701) are named as such rather than left as
  sixteen mystery hex codes, and `encryptedClientHelloOffered` calls out extension `0xfe0d`
  specifically: a client with no ECH configuration is meant to send one anyway, so that
  using ECH and not using it look alike, and whether it was real is deliberately invisible.
- **Responses that are wrong on purpose.** `/hostile/…` hijacks the connection and writes
  resets, truncated bodies and invalid framing directly. `http.ResponseWriter` exists to
  stop a handler emitting nonsense, so nothing above the socket can produce these.

Running it
----------

```
docker build -t okhttp-testbed/test-server test-server
docker run --rm -p 8080:8080 -p 8081:8081 -p 8443:8443 \
  -p 8410:8410 -p 8411:8411 -p 8412:8412 -p 8413:8413 \
  okhttp-testbed/test-server
```

Or the whole stack — this server plus the two third-party fixtures issue #8 says to
self-host rather than depend on:

```
cd test-server && docker compose up -d
```

Locally, without Docker:

```
cd test-server && go run .
```

Published images are at `ghcr.io/yschimke/okhttp-testbed/test-server`, tagged `latest` and
by commit, built by the `test-server` workflow.

Listeners
---------

| Name    | Default | What it is                                                        |
|---------|---------|-------------------------------------------------------------------|
| `http`  | `:8080` | Plain HTTP/1.1                                                    |
| `raw`   | `:8081` | Not an HTTP server: echoes the request head back byte for byte    |
| `https` | `:8443` | TLS 1.2 and 1.3, ALPN offering `h2` then `http/1.1`               |
| `tls10` | `:8410` | TLS 1.0 only, obsolete suites, `http/1.1`                         |
| `tls11` | `:8411` | TLS 1.1 only, obsolete suites, `http/1.1`                         |
| `tls12` | `:8412` | TLS 1.2 only, `http/1.1`                                          |
| `tls13` | `:8413` | TLS 1.3 only, `http/1.1`                                          |
| `badchain-expired` | `:8420` | A leaf that expired yesterday                            |
| `badchain-wrong-host` | `:8421` | A valid chain for a name it is not served on          |
| `badchain-self-signed` | `:8422` | A leaf that is its own issuer                        |
| `badchain-untrusted-root` | `:8423` | Signed by a CA that is never published            |
| `badchain-incomplete-chain` | `:8424` | Signed by an intermediate that is withheld      |

A port per TLS version is how badssl.com does it, and it is what lets a `ConnectionSpec`
test point at a server that will negotiate one version and refuse the rest. The per-version
listeners offer `http/1.1` alone: `h2` requires TLS 1.2 or better, so offering it would make
those listeners differ from the main one in two ways rather than one.

Chains that must be rejected
----------------------------

The `badchain-*` listeners are the local half of the badssl matrix, and they exist because
badssl.com cannot be relied on for it. It is best-effort by its own description, several of
its variants are ordinary certificates in the test set, its weak-crypto ports were deleted in
June 2026 — and its `expired` certificate is signed a day at a time, so for the first
twenty-four hours after generation it is not expired at all and a test asserting rejection
passes for the wrong reason. Minting the chains here makes the validity window exact.

Each listener differs from `https` in **exactly one way**, which is what makes a refusal
informative: a client that refuses `badchain-expired` and accepts `https` has told us
something specific, where a certificate wrong in three ways at once would only say it was
wrong. All five are served on the same names as the good leaf, so the certificate is the
only variable.

`badchain-incomplete-chain` is the one that needs care. Its leaf is signed by an intermediate
the server holds back, so the path cannot be built — but the leaf itself is perfectly good,
and a client that already holds the intermediate will accept it. `TestIncompleteChainVerifiesOnceCompleted` asserts exactly that: the same certificate fails against the CA alone and
succeeds once the withheld intermediate is supplied. Without that, a chain that was simply
broken would look identical.

Revocation is deliberately absent. A revoked certificate needs an OCSP responder or a CRL
distribution point a client will actually fetch, which is different machinery — issue #16,
not a fake bolted onto this.

These listeners need the fixture CA's signing key, so they exist only when the server minted
its own certificate. Under `TLS_CERT_FILE` there are none.

The raw listener exists because `net/http` cannot answer the question it answers. Go
canonicalises header names to `Title-Case` and keeps no record of their order, so `/anything`
reports what Go parsed. Header order and casing are half of how a CDN fingerprints a client,
and the raw port is where a test can see them.

Endpoints
---------

`GET /` lists them all. In outline:

| Path | What it does |
|---|---|
| `/health` | liveness |
| `/info` | listeners, certificate mode, and the URL this request arrived as |
| `/ca.pem` | the generated CA, when the server minted its own certificate |
| `/tls` | the negotiated handshake and the ClientHello it was chosen from |
| `/anything`, `/anything/{path...}` | the whole request echoed back as JSON, any method |
| `/headers` | request headers as `net/http` parsed them |
| `/status/{code}` | that status code, `?location=` to add a `Location` |
| `/redirect/{n}`, `/absolute-redirect/{n}` | a redirect chain, relative or absolute |
| `/redirect-to?url=&status=` | a redirect to a given URL |
| `/delay/{seconds}` | a response after a delay |
| `/bytes/{n}` | random bytes with a `Content-Length` |
| `/stream/{n}` | JSON lines, chunked |
| `/drip?duration=&bytes=&delay=` | a body dribbled out over time |
| `/gzip`, `/deflate` | an encoded body, offered or not |
| `/trailers` | a chunked body with trailing headers |
| `/basic-auth/{user}/{password}` | 401 until those credentials arrive |
| `/cookies`, `/cookies/set?a=b` | a cookie round trip |
| `/cache`, `/cache/{seconds}` | conditional GET, and a `max-age` response |
| `/hostile/…` | responses that are wrong on purpose; `GET /hostile` lists them |

The hostile set, which is the part worth enumerating:

| Path | What arrives |
|---|---|
| `/hostile/no-response` | the connection accepted and closed with nothing written |
| `/hostile/reset?after=` | headers, part of a body, then RST rather than FIN |
| `/hostile/truncated-body?promised=&sent=` | fewer bytes than `Content-Length` promised |
| `/hostile/truncated-chunks` | chunks with no terminating zero-length chunk |
| `/hostile/invalid-chunk-size` | a chunk size that is not hexadecimal |
| `/hostile/invalid-status-line` | a four-digit status code |
| `/hostile/duplicate-content-length` | two `Content-Length` headers that disagree |
| `/hostile/content-length-and-chunked` | both framings at once |
| `/hostile/slow-headers?delay=` | the response head, one byte at a time |
| `/hostile/huge-header?size=` | a response header far past any sane limit |
| `/hostile/informational-storm?count=` | a run of `100 Continue` before the response |
| `/hostile/half-close` | a complete response, then the write half shut |

These hijack the connection, which HTTP/2 does not allow — a hijack there would corrupt
every other stream on the connection. Over `h2` they answer `501` and say so, so a suite
runs them on the plain port or on a listener that negotiates `http/1.1`.

Configuration
-------------

Every listener's address is an environment variable, and setting one to the empty string
turns that listener off.

| Variable | Default | Meaning |
|---|---|---|
| `HTTP_ADDR` | `:8080` | plain HTTP |
| `RAW_ADDR` | `:8081` | raw request-head echo |
| `HTTPS_ADDR` | `:8443` | TLS, with `h2` |
| `TLS10_ADDR` … `TLS13_ADDR` | `:8410` … `:8413` | one TLS version each |
| `BADCHAIN_EXPIRED_ADDR` | `:8420` | the expired chain |
| `BADCHAIN_WRONG_HOST_ADDR` | `:8421` | the wrong-host chain |
| `BADCHAIN_SELF_SIGNED_ADDR` | `:8422` | the self-signed chain |
| `BADCHAIN_UNTRUSTED_ROOT_ADDR` | `:8423` | the untrusted-root chain |
| `BADCHAIN_INCOMPLETE_CHAIN_ADDR` | `:8424` | the incomplete chain |
| `CERT_HOSTS` | — | extra names or IPs the generated leaf covers, comma-separated |
| `TLS_CERT_FILE`, `TLS_KEY_FILE` | — | present a supplied certificate instead of a generated one |

With `TLS_CERT_FILE` and `TLS_KEY_FILE` set — a deployment holding a real certificate — the
chain is whatever those files contain, clients trust it the ordinary way, and `/ca.pem`
answers `404`. With neither set, the server generates a CA and a leaf covering `localhost`,
`127.0.0.1`, `::1` and anything in `CERT_HOSTS`; both are fresh on every start, so a client
has to fetch the CA at run time rather than pin it.

Ports
-----

**A non-standard port does not change the results.** Nothing in the server is configured
with the port it is reached on: every absolute URL it emits — `Location` headers, the `url`
in `/anything`, the `caUrl` in `/info` — is built from the `Host` header the request arrived
with. Deploy it on 18443 and a redirect comes back on 18443. The workflow's smoke test runs
the whole image on deliberately non-default ports for exactly this reason.

Nor does the port reach the things the suites assert on. It is not in the certificate, not
in SNI, not in the ALPN negotiation, and cookies are not port-scoped. `CONNECT` through a
proxy carries it without complaint.

Four caveats, none of them blocking:

1. **Tests must take the port from configuration**, never assume `:443`. That is on the
   suites, not on the server.
2. **A restrictive network is the real risk.** GitHub-hosted runners reach arbitrary ports,
   but corporate networks, hotel Wi-Fi and some mobile carriers allow 443 and little else. A
   suite that is red for a contributor and green in CI costs more than a spare port does.
3. **443 is the only port that tests the internet.** Interception middleboxes, CDN
   behaviour and captive portals mostly act on 443 — so a deployment on 8443 is not being
   handled the way a user's traffic is. If the point of a hosted instance is to see what
   real networks do to a connection, that endpoint wants 443; the per-version and hostile
   ports can be anywhere.
4. **HTTP/3, whenever it matters, needs the same number as a UDP port**, and the `Alt-Svc`
   advertisement has to name it. Nothing here speaks h3 today — Caddy in the compose stack
   does, and its ports are mapped in pairs for that reason.

So: put `https` on 443 if the host has it free, and leave everything else wherever it fits.
Nothing breaks if 443 isn't available.

The compose stack
-----------------

| Service | Image | Why |
|---|---|---|
| `test-server` | built here | the above |
| `httpbin` | `ghcr.io/mccutchen/go-httpbin:v2.16.1` | the httpbin endpoint vocabulary, self-hosted rather than rate-limited on httpbin.org |
| `caddy` | `caddy:2.11.4-alpine` | an independent h2 stack, plus h2c and an `Alt-Svc` h3 advertisement |

Image tags are pinned, as `mockserver` is: a moved image is breakage the daily run should
catch rather than absorb. Every published port is a `${VAR:-default}`, so a host that
can't spare 8080 or 8443 sets variables and changes nothing else.

Tests
-----

```
cd test-server && go test ./...
```

They run against real listeners rather than calling handlers directly — the framing, the
hijacked responses and the handshake reporting only exist on a connection. The `test-server`
workflow runs them, builds the image, and smoke-tests the running container on non-default
ports before publishing it.
