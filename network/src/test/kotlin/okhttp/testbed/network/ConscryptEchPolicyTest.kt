/*
 * Copyright (c) 2026 OkHttp Authors
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

import assertk.assertThat
import assertk.assertions.contains
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt
import org.conscrypt.DomainEncryptionMode
import org.conscrypt.NetworkSecurityPolicy
import org.conscrypt.metrics.CertificateTransparencyVerificationReason
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile

/**
 * Reproduces the socket path in https://github.com/google/conscrypt/issues/1562.
 *
 * The ECH origin runs in the same Testcontainers fixture used by the Android tests. Its metadata
 * supplies a real, paired ECH config and a fixture CA. No public DNS or public ECH server is
 * involved. The configured trust manager deliberately extends [X509ExtendedTrustManager], since
 * that is the case the issue says loses the custom network security policy in a Conscrypt socket.
 */
class ConscryptEchPolicyTest {
  @Test
  fun extendedTrustManagerPolicyAllowsEchOnEngineSocket() {
    withOrigin { origin ->
      val metadata = metadata(origin)

      val trustManager =
        ExtendedEchPolicyTrustManager(
          conscryptTrustManager(metadata.required("CA_CERT")),
        )
      val sslContext =
        SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
          init(null, arrayOf(trustManager), null)
        }
      val port = origin.getMappedPort(TLS_PORT)
      val innerName = "green.secret.test"
      val socket =
        sslContext
          .socketFactory
          .createSocket(Socket(origin.host, port), innerName, port, true) as SSLSocket
      socket.use {
        Conscrypt.setEchConfigList(it, metadata.required("ECH_GREEN_CONFIG_LIST"))
        it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        it.startHandshake()

        // This is a full TLS handshake against the ECH-enabled server, not just a ClientHello
        // byte check: the server reports whether it decrypted and accepted the inner hello.
        it.outputStream.write(
          "GET / HTTP/1.1\r\nHost: $innerName\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        val response = it.inputStream.bufferedReader().readText()
        assertThat(response).contains("\"echAccepted\":true")
        assertThat(response).contains("\"serverName\":\"$innerName\"")
      }
    }
  }

  /**
   * Reproduces issue #1561 with a stale synthetic config. On ECH rejection, the fixture presents
   * a certificate valid only for the public name. HTTPS endpoint verification must use that name
   * for the rejected-ECH connection; verifying the original inner name fails with a SAN mismatch.
   *
   * This uses SSLEngine directly, as the reporter notes that the SSLContext socket-factory path
   * is affected by #1562 and therefore cannot isolate this issue.
   */
  @Test
  fun rejectedEchVerifiesCertificateForPublicName() {
    withOrigin { origin ->
      val metadata = metadata(origin)
      val trustManager = conscryptTrustManager(metadata.required("CA_CERT"))
      val sslContext = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
        init(null, arrayOf(trustManager), null)
      }
      val port = origin.getMappedPort(TLS_PORT)
      val innerName = "issue1561.secret.test"
      val engine =
        sslContext.createSSLEngine(innerName, port).apply {
          useClientMode = true
          sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        }
      Conscrypt.setEchConfigList(engine, metadata.required("ECH_ISSUE1561_STALE_CONFIG_LIST"))

      SocketChannel.open().use { channel ->
        channel.configureBlocking(true)
        channel.connect(InetSocketAddress(origin.host, port))
        engine.beginHandshake()
        doHandshake(engine, channel)
      }
    }
  }

  private fun withOrigin(block: (GenericContainer<Nothing>) -> Unit) {
    val image =
      ImageFromDockerfile("okhttp/testbed-ech-conscrypt:local", false)
        .withFileFromClasspath("Dockerfile", "ech-fixture/Dockerfile")
        .withFileFromClasspath("main.go", "ech-fixture/main.go")
        .withFileFromClasspath("ct-fixture-ca.pem", "ech-fixture/ct-fixture-ca.pem")
        .withFileFromClasspath("ct-fixture-ca-key.pem", "ech-fixture/ct-fixture-ca-key.pem")
    val origin = GenericContainer<Nothing>(image)
    origin.withCommand("target")
    origin.withExposedPorts(CONTROL_PORT, TLS_PORT)
    origin.waitingFor(Wait.forHttp("/health").forPort(CONTROL_PORT))
    origin.start()
    try {
      block(origin)
    } finally {
      origin.stop()
    }
  }

  private fun metadata(origin: GenericContainer<Nothing>): Map<String, ByteArray> =
    URL("http://${origin.host}:${origin.getMappedPort(CONTROL_PORT)}/metadata")
      .readText()
      .lines()
      .filter(String::isNotBlank)
      .associate { line ->
        val (key, value) = line.split('=', limit = 2)
        key to Base64.getDecoder().decode(value)
      }

  private fun doHandshake(
    engine: javax.net.ssl.SSLEngine,
    channel: SocketChannel,
  ) {
    val networkIn = ByteBuffer.allocateDirect(64 * 1024)
    val networkOut = ByteBuffer.allocateDirect(64 * 1024)
    val empty = ByteBuffer.allocateDirect(0)
    while (true) {
      when (engine.handshakeStatus.toString()) {
        "NEED_TASK" -> {
          while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
          }
        }
        "NEED_WRAP" -> {
          networkOut.clear()
          engine.wrap(empty, networkOut)
          networkOut.flip()
          while (networkOut.hasRemaining()) channel.write(networkOut)
        }
        "NEED_UNWRAP" -> {
          if (channel.read(networkIn) == -1) throw SSLHandshakeException("peer closed during handshake")
          networkIn.flip()
          val result = engine.unwrap(networkIn, empty)
          networkIn.compact()
          if (result.status == javax.net.ssl.SSLEngineResult.Status.CLOSED) {
            throw SSLHandshakeException("peer closed during handshake")
          }
        }
        "NEED_UNWRAP_AGAIN" -> {
          networkIn.flip()
          engine.unwrap(networkIn, empty)
          networkIn.compact()
        }
        "FINISHED", "NOT_HANDSHAKING" -> return
        else -> throw IllegalStateException("Unexpected handshake status ${engine.handshakeStatus}")
      }
    }
  }

  private fun conscryptTrustManager(caPem: ByteArray): X509TrustManager {
    val certificate =
      CertificateFactory
        .getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(caPem))
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null)
      setCertificateEntry("ech-test-fixture", certificate)
    }
    return javax.net.ssl.TrustManagerFactory
      .getInstance("PKIX", Conscrypt.newProvider())
      .apply { init(keyStore) }
      .trustManagers
      .filterIsInstance<X509TrustManager>()
      .single()
  }

  private fun Map<String, ByteArray>.required(key: String): ByteArray =
    requireNotNull(this[key]) { "ECH fixture metadata missing $key" }

  private companion object {
    const val CONTROL_PORT = 8080
    const val TLS_PORT = 8443
  }
}

/** Extended trust-manager shape plus the same policy hook supplied by Android's platform. */
class ExtendedEchPolicyTrustManager(
  private val delegate: X509TrustManager,
) : X509ExtendedTrustManager() {
  @Suppress("unused") // Conscrypt looks this up reflectively on the configured trust manager.
  fun getNetworkSecurityPolicy(): NetworkSecurityPolicy = Policy

  override fun getAcceptedIssuers() = delegate.acceptedIssuers

  override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) =
    delegate.checkClientTrusted(chain, authType)

  override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) =
    delegate.checkServerTrusted(chain, authType)

  override fun checkClientTrusted(
    chain: Array<java.security.cert.X509Certificate>,
    authType: String,
    socket: Socket,
  ) = extended { it.checkClientTrusted(chain, authType, socket) } ?: delegate.checkClientTrusted(chain, authType)

  override fun checkServerTrusted(
    chain: Array<java.security.cert.X509Certificate>,
    authType: String,
    socket: Socket,
  ) = extended { it.checkServerTrusted(chain, authType, socket) } ?: delegate.checkServerTrusted(chain, authType)

  override fun checkClientTrusted(
    chain: Array<java.security.cert.X509Certificate>,
    authType: String,
    engine: SSLEngine,
  ) = extended { it.checkClientTrusted(chain, authType, engine) } ?: delegate.checkClientTrusted(chain, authType)

  override fun checkServerTrusted(
    chain: Array<java.security.cert.X509Certificate>,
    authType: String,
    engine: SSLEngine,
  ) = extended { it.checkServerTrusted(chain, authType, engine) } ?: delegate.checkServerTrusted(chain, authType)

  private inline fun <T> extended(block: (X509ExtendedTrustManager) -> T): T? =
    (delegate as? X509ExtendedTrustManager)?.let(block)

  private object Policy : NetworkSecurityPolicy {
    override fun isCertificateTransparencyVerificationRequired(hostname: String) = false

    override fun getCertificateTransparencyVerificationReason(hostname: String) =
      CertificateTransparencyVerificationReason.UNKNOWN

    override fun getDomainEncryptionMode(hostname: String) = DomainEncryptionMode.ENABLED
  }
}
