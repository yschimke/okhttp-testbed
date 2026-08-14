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
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Reads the client hello Conscrypt actually sends, and checks that the name is not in it.
 *
 * Every other ECH suite here asks a server whether it saw an encrypted client hello, which means
 * every other ECH suite depends on somebody's uptime and on a network that doesn't intercept TLS.
 * This one asks the only question that has to be true for any of the rest to be — did the bytes
 * on the wire carry the name in the clear — and answers it against a socket that accepts a
 * connection and says nothing. It needs no DNS, no internet and no server, so it is the suite
 * that says whether a red result elsewhere is about ECH or about the network the run was on.
 *
 * The config list here is synthetic. A real one comes from a DNS `HTTPS` record and is paired
 * with a key the server holds; nothing here completes a handshake, so an unpaired public key is
 * fine. It does have to be a well-formed `ECHConfigList` — BoringSSL rejects a malformed one, and
 * a rejected config means a client hello with no ECH in it, which is exactly the failure this
 * would otherwise miss.
 */
class EchClientHelloTest {
  @Test
  fun theNameIsNotSentInTheClear() {
    assumeTrue(ConscryptEch.isSupported) {
      "requires a Conscrypt with ECH. Run conscrypt/fetch-conscrypt.sh."
    }

    val trustManager = EchEnablingTrustManager(ConscryptEch.platformTrustManager())
    val sslContext = ConscryptEch.sslContext(trustManager)

    val clientHello = captureClientHello { server ->
      val socket =
        sslContext.socketFactory.createSocket(
          Socket(InetAddress.getLoopbackAddress(), server.localPort),
          INNER_NAME,
          server.localPort,
          true,
        ) as SSLSocket
      org.conscrypt.Conscrypt.setEchConfigList(socket, echConfigList(PUBLIC_NAME))
      try {
        socket.startHandshake()
      } catch (_: IOException) {
        // Nothing answers. The first flight is all this needs.
      }
    }

    // 0xfe0d is both the `encrypted_client_hello` extension type and the ECHConfig version, which
    // is why matching the two bytes anywhere in the hello is enough: an ECH-less hello from this
    // stack contains neither.
    assertThat(clientHello.containsBytes(byteArrayOf(0xfe.toByte(), 0x0d))).isTrue()

    // The outer hello names the public name, and the real one is encrypted. Both halves matter:
    // a client that sent no SNI at all would pass the second check on its own.
    assertThat(clientHello.containsBytes(PUBLIC_NAME.toByteArray(Charsets.US_ASCII))).isTrue()
    assertThat(clientHello.containsBytes(INNER_NAME.toByteArray(Charsets.US_ASCII))).isFalse()
  }

  /** Runs [connect] against a socket that accepts once, reads the first flight, and closes. */
  private fun captureClientHello(connect: (ServerSocket) -> Unit): ByteArray {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      var captured = ByteArray(0)
      val accepter =
        thread {
          try {
            server.accept().use { accepted ->
              val buffer = ByteArray(16 * 1024)
              val read = accepted.getInputStream().read(buffer)
              if (read > 0) captured = buffer.copyOf(read)
            }
          } catch (_: IOException) {
          }
        }

      connect(server)
      accepter.join(5_000)
      return captured
    }
  }

  private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start ->
      needle.indices.all { this[start + it] == needle[it] }
    }

  /**
   * An `ECHConfigList` for [publicName], from RFC 9849 section 4.
   *
   * ```
   * ECHConfigList   uint16 length, then ECHConfig*
   * ECHConfig       uint16 version, uint16 length, ECHConfigContents
   * ```
   */
  private fun echConfigList(publicName: String): ByteArray {
    val publicKey = ByteArray(32).also(SecureRandom()::nextBytes)

    val contents =
      ByteArrayOutputStream().apply {
        write(CONFIG_ID)
        writeShort(KEM_X25519_HKDF_SHA256)
        writeShort(publicKey.size)
        write(publicKey)
        writeShort(4) // cipher_suites, one suite of two uint16s.
        writeShort(KDF_HKDF_SHA256)
        writeShort(AEAD_AES_128_GCM)
        write(MAXIMUM_NAME_LENGTH)
        val name = publicName.toByteArray(Charsets.US_ASCII)
        write(name.size)
        write(name)
        writeShort(0) // No extensions.
      }.toByteArray()

    val config =
      ByteArrayOutputStream().apply {
        writeShort(ECH_CONFIG_VERSION)
        writeShort(contents.size)
        write(contents)
      }.toByteArray()

    return ByteArrayOutputStream().apply {
      writeShort(config.size)
      write(config)
    }.toByteArray()
  }

  private fun OutputStream.writeShort(value: Int) {
    write((value ushr 8) and 0xff)
    write(value and 0xff)
  }

  private companion object {
    /** Neither name is resolved or connected to; they only have to be distinguishable. */
    private const val PUBLIC_NAME = "public.example.com"
    private const val INNER_NAME = "secret.example.com"

    private const val ECH_CONFIG_VERSION = 0xfe0d
    private const val CONFIG_ID = 42
    private const val KEM_X25519_HKDF_SHA256 = 0x0020
    private const val KDF_HKDF_SHA256 = 0x0001
    private const val AEAD_AES_128_GCM = 0x0001
    private const val MAXIMUM_NAME_LENGTH = 64
  }
}
