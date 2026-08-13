/*
 * Copyright (C) 2026 Block, Inc.
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
package okhttp.testbed.ech

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile

/**
 * The two containers the Android ECH suite runs against: a DoH resolver and an HTTPS origin
 * that speaks Encrypted Client Hello.
 *
 * This is not a test. The tests are Android instrumentation tests and run on a device or an
 * emulator, which has no Docker; the containers have to live on the host, and the device
 * reaches them over `adb reverse`. So the fixture runs as its own process: it starts both
 * containers, writes their host ports and the fixture CA to the file named by
 * `ECH_FIXTURE_ENDPOINT_FILE`, and stays up until that file is deleted. `run-ech-test.sh`
 * is what drives it — see the ECH suite's section in the README.
 */
object EchFixtureService {
  private const val CONTROL_PORT = 8080
  private const val DOH_PORT = 8053
  private const val TARGET_PORT = 8443

  @JvmStatic
  fun main(args: Array<String>) {
    require(args.isEmpty()) { "This service does not accept command-line arguments" }

    val endpointFile =
      Path.of(
        requireNotNull(System.getenv("ECH_FIXTURE_ENDPOINT_FILE")) {
          "ECH_FIXTURE_ENDPOINT_FILE is not set"
        },
      )
    val image =
      ImageFromDockerfile("okhttp/testbed-ech-fixture:local", false)
        .withFileFromClasspath("Dockerfile", "ech-fixture/Dockerfile")
        .withFileFromClasspath("main.go", "ech-fixture/main.go")

    // The origin generates the CA, the leaf certificates and the ECH keys, then reports them
    // on a plain HTTP control port. The resolver is configured from that, so both containers
    // agree on the config lists without anything being pinned in this repository.
    val target = GenericContainer<Nothing>(image)
    target.withCommand("target")
    target.withExposedPorts(CONTROL_PORT, TARGET_PORT)
    target.waitingFor(Wait.forHttp("/health").forPort(CONTROL_PORT))
    // Building the Go image from source on a cold machine is the slow part, not the boot.
    target.withStartupTimeout(Duration.ofMinutes(10))
    var doh: GenericContainer<Nothing>? = null
    val stopped = AtomicBoolean()
    val stop = {
      if (stopped.compareAndSet(false, true)) {
        doh?.stop()
        target.stop()
      }
    }

    target.start()
    val targetHost = target.host.normalizedLoopback()
    val metadata =
      URI("http://$targetHost:${target.getMappedPort(CONTROL_PORT)}/metadata")
        .toURL()
        .readText()
        .lineSequence()
        .filter { it.isNotEmpty() }
        .associate { line ->
          val (name, value) = line.split('=', limit = 2)
          name to value
        }

    val dohContainer = GenericContainer<Nothing>(image)
    dohContainer.withCommand("doh")
    dohContainer.withEnv("ECH_GREEN_CONFIG_LIST", metadata.required("ECH_GREEN_CONFIG_LIST"))
    dohContainer.withEnv("ECH_RETRY_STALE_CONFIG_LIST", metadata.required("ECH_RETRY_STALE_CONFIG_LIST"))
    dohContainer.withEnv(
      "ECH_DISABLED_STALE_CONFIG_LIST",
      metadata.required("ECH_DISABLED_STALE_CONFIG_LIST"),
    )
    dohContainer.withEnv("DOH_CERT", metadata.required("DOH_CERT"))
    dohContainer.withEnv("DOH_KEY", metadata.required("DOH_KEY"))
    // The port the device dials, not the port the container is published on: the HTTPS
    // record sends the client to 127.0.0.1:8443, which `adb reverse` forwards to the host.
    dohContainer.withEnv("TARGET_PORT", TARGET_PORT.toString())
    dohContainer.withExposedPorts(DOH_PORT)
    dohContainer.waitingFor(Wait.forHttps("/health").forPort(DOH_PORT).allowInsecure())
    dohContainer.withStartupTimeout(Duration.ofMinutes(5))
    doh = dohContainer
    dohContainer.start()

    Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "ech-fixture-service-shutdown"))
    try {
      val endpoints =
        """
        DOH_HOST_PORT=${dohContainer.getMappedPort(DOH_PORT)}
        TARGET_HOST_PORT=${target.getMappedPort(TARGET_PORT)}
        CA_CERT=${metadata.required("CA_CERT")}
        """.trimIndent() + "\n"
      endpointFile.parent?.let { Files.createDirectories(it) }
      Files.writeString(endpointFile, endpoints)
      println("ECH fixture ready on $targetHost")

      // Deleting the endpoint file is how the script says it is done with the containers.
      while (Files.exists(endpointFile)) {
        Thread.sleep(250L)
      }
    } finally {
      Files.deleteIfExists(endpointFile)
      stop()
    }
  }

  private fun String.normalizedLoopback() = if (this == "localhost") "127.0.0.1" else this

  private fun Map<String, String>.required(name: String) =
    requireNotNull(this[name]) { "ECH fixture metadata does not contain $name" }
}
