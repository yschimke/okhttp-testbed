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
package okhttp.testbed.containers

import java.io.File
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile

/**
 * This repository's own HTTP and TLS server, as a container.
 *
 * Built from `test-server/` rather than pulled, so a suite tests the server in the tree beside
 * it rather than whatever was last published. Testcontainers caches the built image by the
 * hash of its context, so the cost is paid once per change however many suites ask for it.
 *
 * Shared because more than one suite needs it: the chains that must be rejected, and the
 * responses that are wrong on purpose.
 */
object TestServer {
  /** Plain HTTP/1.1. The only listener the hostile endpoints work on — see [hostilePort]. */
  const val PLAIN_PORT = 8080

  /** TLS 1.2 and 1.3, ALPN offering h2 then http/1.1. */
  const val TLS_PORT = 8443

  /**
   * Not an HTTP server: it echoes the request head back byte for byte.
   *
   * `net/http` canonicalises header names and keeps no record of their order, so `/anything`
   * reports what Go parsed rather than what OkHttp sent. Header order and casing are half of how
   * a CDN fingerprints a client, and this is the only port where a test can see them.
   */
  const val RAW_PORT = 8081

  /**
   * A port per TLS version, the way badssl.com does it.
   *
   * Each listener negotiates its own version and refuses every other, which is what lets a
   * `ConnectionSpec` assertion be about the spec rather than about what two ends happened to
   * agree on. 1.0 and 1.1 are enabled explicitly by the server — Go refuses them by default —
   * and may still be unreachable because the *client's* platform disabled them, which is a
   * result worth recording rather than a fixture that failed.
   */
  const val TLS10_PORT = 8410
  const val TLS11_PORT = 8411
  const val TLS12_PORT = 8412
  const val TLS13_PORT = 8413

  /**
   * Mutual TLS: a client certificate is required here and merely welcome everywhere else.
   *
   * The distinction is what makes the assertions possible. `RequestClientCert` on the other
   * listeners means a client with no certificate is served anyway, so "presented" and "ignored"
   * are indistinguishable; this one refuses, and `/client.pem` is where a test gets an identity
   * the fixture CA has signed.
   */
  const val MTLS_PORT = 8425

  /** A port per chain a client must refuse. */
  const val EXPIRED_PORT = 8420
  const val WRONG_HOST_PORT = 8421
  const val SELF_SIGNED_PORT = 8422
  const val UNTRUSTED_ROOT_PORT = 8423
  const val INCOMPLETE_CHAIN_PORT = 8424

  /**
   * The hostile endpoints answer on the plain port and not the TLS one.
   *
   * They work by hijacking the connection to write bytes no HTTP library would produce, and a
   * hijack is only possible under HTTP/1.1. The TLS listener offers h2 in ALPN, where the
   * server answers `501` with an explanation instead. Testing them over TLS would therefore
   * assert something about ALPN rather than about malformed responses.
   */
  val hostilePort = PLAIN_PORT

  private val ALL_PORTS =
    arrayOf(
      PLAIN_PORT,
      TLS_PORT,
      RAW_PORT,
      TLS10_PORT,
      TLS11_PORT,
      TLS12_PORT,
      TLS13_PORT,
      MTLS_PORT,
      EXPIRED_PORT,
      WRONG_HOST_PORT,
      SELF_SIGNED_PORT,
      UNTRUSTED_ROOT_PORT,
      INCOMPLETE_CHAIN_PORT,
    )

  // The directory comes from the build rather than a relative path, so this doesn't depend on
  // the working directory a test happens to run in.
  private val CONTEXT: String =
    checkNotNull(System.getProperty("testbed.testServerDir")) {
      "testbed.testServerDir is not set — run these tests through Gradle, which supplies it"
    }

  fun container(): GenericContainer<*> =
    GenericContainer(ImageFromDockerfile().withFileFromPath(".", File(CONTEXT).toPath()))
      .withExposedPorts(*ALL_PORTS)
      .waitingFor(Wait.forHttp("/health").forPort(PLAIN_PORT))
}
