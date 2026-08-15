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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The `HTTPS` record parameters the internet does not publish.
 *
 * `HttpsRecordTest` asks the public names what they carry, and they carry the same two things:
 * `alpn` and the address hints. RFC 9460 defines rather more, and the rest is close to
 * unobtainable in the wild — which is exactly why a client's handling of it goes untested. The
 * ECH fixture's resolver publishes them on purpose, one name per parameter, each differing from an
 * ordinary record in a single way.
 *
 * The resolver is the one the Android ECH suite uses, run here in `doh` mode alone. Its TLS
 * certificate is minted by this test and handed over in the environment, so nothing is pinned and
 * nothing is trusted that this test did not create.
 *
 * The names are under `.test`, reserved by RFC 2606, so they cannot collide with anything real.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SvcParamTest {
  private lateinit var dns: DnsOverHttps

  @BeforeAll
  fun pointAResolverAtTheFixture() {
    val certificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(RESOLVER_CERTIFICATE.certificate)
        .build()

    val bootstrap =
      OkHttpClient
        .Builder()
        .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
        .callTimeout(Duration.ofSeconds(20))
        .build()

    dns =
      DnsOverHttps
        .Builder()
        .client(bootstrap)
        .url("https://$RESOLVER_NAME:${resolver.getMappedPort(DOH_PORT)}/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName(resolver.host))
        .includeServiceMetadata(true)
        .build()
  }

  /**
   * `no-default-alpn` means the list is the whole list.
   *
   * §7.1.1 implies the service's default ALPN — `http/1.1` — into any record carrying `alpn`,
   * unless this parameter says otherwise. So `alpn=h2` alone means two protocols and `alpn=h2`
   * with `no-default-alpn` means one, and a client that ignored the parameter would believe an
   * origin speaks something it has explicitly disclaimed. No public name publishes this.
   */
  @Test
  fun noDefaultAlpnRemovesTheImpliedProtocol() {
    val alpn = metadataFor(NO_DEFAULT_ALPN).alpnIds

    assertThat(alpn, name = "$NO_DEFAULT_ALPN ALPN ids").isNotNull().contains(Protocol.HTTP_2)
    assertThat(alpn!!, name = "the default ALPN, suppressed").doesNotContain(Protocol.HTTP_1_1)
  }

  /**
   * A `mandatory` parameter naming something understood does not spoil the record.
   *
   * `mandatory` lists the parameters a client must understand or else ignore the whole record.
   * Here it names `alpn`, which every client understands, so the correct behaviour is to *use*
   * the record. The failure this catches is a client that treats the presence of `mandatory` as
   * too hard and discards a record it was perfectly able to read.
   */
  @Test
  fun aMandatoryParameterThatIsUnderstoodIsHonoured() {
    val metadata = metadataFor(MANDATORY)

    assertThat(metadata.alpnIds, name = "$MANDATORY ALPN ids").isNotNull().isNotEmpty()
    assertThat(metadata.port, name = "$MANDATORY port").isEqualTo(TARGET_PORT)
  }

  /**
   * An unregistered parameter is ignored rather than fatal.
   *
   * The parameter registry exists to be extended, so the whole design depends on clients skipping
   * what they do not recognise. A client that rejected the record would stop understanding names
   * the moment anybody adopted something new — a failure that arrives years after the code was
   * written and nowhere near it.
   */
  @Test
  fun anUnknownParameterDoesNotSpoilTheRecord() {
    val metadata = metadataFor(UNKNOWN_PARAM)

    assertThat(metadata.alpnIds, name = "$UNKNOWN_PARAM ALPN ids").isNotNull().isNotEmpty()
    assertThat(metadata.port, name = "$UNKNOWN_PARAM port").isEqualTo(TARGET_PORT)
  }

  /**
   * AliasMode is not followed, and the name still resolves.
   *
   * A priority-zero record names a target rather than binding a service, and OkHttp does not
   * surface one: the name resolves through its A record and no `ServiceMetadata` arrives. That is
   * recorded here as the current answer rather than asserted as the right one — following an alias
   * is a resolver's job at least as much as a client's — but the half that *is* a promise is that
   * an AliasMode record must not break ordinary resolution, and this asserts it.
   */
  @Test
  fun anAliasModeRecordIsIgnoredAndTheNameStillResolves() {
    val records = dns.records(ALIAS)

    assertThat(
      records.filterIsInstance<Dns.Record.IpAddress>(),
      name = "$ALIAS addresses",
    ).isNotEmpty()

    assertThat(
      records.filterIsInstance<Dns.Record.ServiceMetadata>(),
      name = "$ALIAS service metadata, which OkHttp does not surface for AliasMode",
    ).isEmpty()
  }

  /**
   * A record offering only a protocol this client cannot speak.
   *
   * `alpn=h3` with no `h2` is the shape that would break connection setup if a client read the
   * list as a requirement: OkHttp has no HTTP/3, and concluding the origin is unreachable would be
   * wrong — `http/1.1` is implied into the set and is perfectly usable. The other half of the
   * assertion is that `h2` is *not* invented to make the list more palatable, because a client
   * that did would try a protocol the origin never offered.
   */
  @Test
  fun anAlpnListWithoutH2IsReadWithoutInventingIt() {
    val alpn = metadataFor(H3_ONLY).alpnIds

    assertThat(alpn, name = "$H3_ONLY ALPN ids").isNotNull().contains(Protocol.HTTP_1_1)
    assertThat(alpn!!, name = "h2, which the record does not offer").doesNotContain(Protocol.HTTP_2)
  }

  private fun metadataFor(hostname: String): Dns.Record.ServiceMetadata {
    val metadata = dns.records(hostname).filterIsInstance<Dns.Record.ServiceMetadata>()
    assertThat(metadata, name = "$hostname HTTPS records").isNotEmpty()
    return metadata.first()
  }

  /** As in the network module: `newCall` is asynchronous, and `lookup` cannot carry parameters. */
  private fun Dns.records(hostname: String): List<Dns.Record> {
    val latch = CountDownLatch(1)
    val collected = mutableListOf<Dns.Record>()
    val failure = AtomicReference<IOException>()

    newCall(Dns.Request(hostname)).enqueue(
      object : Dns.Callback {
        override fun onRecords(
          call: Dns.Call,
          last: Boolean,
          records: List<Dns.Record>,
        ) {
          synchronized(collected) { collected += records }
          if (last) latch.countDown()
        }

        override fun onFailure(
          call: Dns.Call,
          e: IOException,
        ) {
          failure.set(e)
          latch.countDown()
        }
      },
    )

    check(latch.await(30, TimeUnit.SECONDS)) { "$hostname: the resolver never called back" }
    failure.get()?.let { throw it }
    return synchronized(collected) { collected.toList() }
  }

  companion object {
    const val DOH_PORT = 8053

    /** What the fixture's records point their `port` parameter at. */
    const val TARGET_PORT = 8443

    const val ALIAS = "alias.svcb.test"
    const val NO_DEFAULT_ALPN = "nodefaultalpn.svcb.test"
    const val MANDATORY = "mandatory.svcb.test"
    const val UNKNOWN_PARAM = "unknownparam.svcb.test"
    const val H3_ONLY = "h3only.svcb.test"

    /**
     * The name the resolver is reached by, and the one its certificate covers.
     *
     * A name rather than an address because `DnsOverHttps` needs a URL, and a URL with an IP in it
     * would need the certificate to carry an IP SAN — one more thing to get right for no benefit.
     * `bootstrapDnsHosts` is what actually resolves it.
     */
    const val RESOLVER_NAME = "resolver.svcb.test"

    /** Minted here, handed to the container in the environment, and trusted by nothing else. */
    val RESOLVER_CERTIFICATE: HeldCertificate =
      HeldCertificate
        .Builder()
        .addSubjectAlternativeName(RESOLVER_NAME)
        .build()

    private fun base64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())

    /**
     * The ECH fixture's Go source, supplied by the build.
     *
     * The directory rather than a dependency on `:ech-fixture`: that module's Kotlin targets a
     * newer JDK than these suites compile for, and the resolver is Go. Same mechanism
     * [TestServer] uses, and for the same reason — a relative path would depend on the working
     * directory a test happened to run in.
     */
    private val CONTEXT: String =
      checkNotNull(System.getProperty("testbed.echFixtureDir")) {
        "testbed.echFixtureDir is not set — run these tests through Gradle, which supplies it"
      }

    /**
     * Configured with statements rather than a fluent chain.
     *
     * `GenericContainer<Nothing>` makes every self-returning method return `Nothing`, so chaining
     * stops compiling after the first call. `EchFixtureService` does the same thing for the same
     * reason.
     */
    @Container
    @JvmStatic
    val resolver: GenericContainer<Nothing> =
      GenericContainer<Nothing>(ImageFromDockerfile().withFileFromPath(".", File(CONTEXT).toPath())).apply {
        withCommand("doh")
        withExposedPorts(DOH_PORT)
        withEnv("DOH_CERT", base64(RESOLVER_CERTIFICATE.certificatePem()))
        withEnv("DOH_KEY", base64(RESOLVER_CERTIFICATE.privateKeyPkcs8Pem()))
        // The ECH names are not what this suite asks about, and the resolver requires the
        // variables to be set. Two zero bytes is a well-formed empty config list.
        withEnv("ECH_GREEN_CONFIG_LIST", "AAA=")
        withEnv("ECH_RETRY_STALE_CONFIG_LIST", "AAA=")
        withEnv("ECH_DISABLED_STALE_CONFIG_LIST", "AAA=")
        withEnv("TARGET_PORT", TARGET_PORT.toString())
        waitingFor(Wait.forHttp("/health").forPort(DOH_PORT).usingTls().allowInsecure())
        // Building the Go image from source on a cold machine is the slow part, not the boot.
        withStartupTimeout(Duration.ofMinutes(10))
      }
  }
}
