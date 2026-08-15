plugins {
  alias(libs.plugins.kotlin.jvm)
}

// The version of OkHttp under test. Override to check a release candidate or a snapshot:
//   ./gradlew test -PokhttpVersion=5.5.0-SNAPSHOT
val okhttpVersion =
  providers
    .gradleProperty("okhttpVersion")
    .getOrElse(libs.versions.okhttp.get())

// A snapshot has to be re-resolved every run. Gradle caches changing modules for 24 hours,
// and the daily job restores the dependency cache from the previous run, so without this a
// run can quietly test yesterday's build under today's name — exactly the failure mode a
// daily snapshot run exists to avoid. Releases are immutable, so this only applies to
// snapshots and leaves the normal build's caching alone.
if (okhttpVersion.endsWith("-SNAPSHOT")) {
  configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
  }
}

// Encrypted Client Hello arrived after 5.4.0: `Route.echConfigList` and
// `DnsOverHttps.Builder.includeServiceMetadata` don't exist in earlier releases, so EchTest
// wouldn't compile against them. Suites here have to build against any version the testbed is
// pointed at, so the source is left out entirely when the version under test predates the API.
val echVersion = listOf(5, 5, 0)

val supportsEch =
  okhttpVersion
    .substringBefore("-")
    .split(".")
    .mapNotNull(String::toIntOrNull)
    .let { version -> version.size == echVersion.size && compareVersions(version, echVersion) >= 0 }

fun compareVersions(
  a: List<Int>,
  b: List<Int>,
): Int = a.zip(b).firstNotNullOfOrNull { (x, y) -> (x - y).takeIf { it != 0 } } ?: 0

val echTestPattern = "EchTest"
val echConscryptTestPattern = "EchConscryptTest"
val echClientHelloTestPattern = "EchClientHelloTest"
// The suites and helpers that name `Dns.Record` or `includeServiceMetadata`, neither of which
// exists below 5.5.0.
val serviceMetadataPatterns = listOf("DohServiceMetadataTest", "HttpsRecordTest", "DnsRecords")

// The Conscrypt built from `google3-export`, if someone has fetched or built it. It is not on
// any repository — `Conscrypt.setEchConfigList` exists on that branch and in no release — so
// `conscrypt/fetch-conscrypt.sh` stages it here. Absent, the suite that needs it is left out of
// the build entirely rather than failing to compile. See conscrypt/README.md.
val conscryptJars =
  fileTree(rootProject.layout.projectDirectory.dir("conscrypt/build/dist")) {
    include("conscrypt-openjdk-*.jar")
  }

val hasConscrypt = !conscryptJars.isEmpty

sourceSets {
  test {
    kotlin {
      // EchTest joined this list when it became parameterised over the platforms: it names
      // EchConscryptPlatform, so it can no longer compile without a Conscrypt to build it on.
      // The workflow fetches one before running, and a run that couldn't reports no ECH suites
      // rather than a suite that silently tests half of what it says it does.
      if (!supportsEch || !hasConscrypt) {
        exclude(
          "**/$echTestPattern.kt",
          "**/$echConscryptTestPattern.kt",
          "**/$echClientHelloTestPattern.kt",
          "**/ConscryptEch.kt",
          "**/EchConscryptPlatform.kt",
        )
      }

      // `DnsOverHttps.Builder.includeServiceMetadata` and `Dns.Record` shipped in the same
      // release as the ECH API, so this suite needs the version check but not the Conscrypt one:
      // it asks what the resolver returned, which no TLS stack is involved in.
      if (!supportsEch) {
        exclude(*serviceMetadataPatterns.map { "**/$it.kt" }.toTypedArray())
      }
    }
  }
}

// Nothing in this module gates. Every test here calls a server someone else operates —
// Google, Cloudflare, Let's Encrypt, tls-ech.dev, defo.ie — and so can fail for reasons that
// are nothing to do with OkHttp or with this repository: a rate limit, a certificate renewed
// overnight, a runner behind a captive resolver. That is the loomTest situation, a result
// worth recording rather than a build worth failing, so `test` is disabled here and every
// suite runs under a task carrying ignoreFailures. See the README.
//
// Disabling `test` rather than moving one class out of it is the point: a suite added here
// later cannot end up gating the build by being written in the wrong file.
tasks.test {
  enabled = false
}

// Where the reachability preflight publishes what it found. One file per task, because both
// tasks run in this module's build directory and would otherwise write over each other; the
// workflow uploads them alongside the JUnit XML and `collect_results.py` reads them into the
// endpoint availability table. Named after the task for the same reason the XML is.
fun Test.reportEndpointsTo(task: String) {
  val report = layout.buildDirectory.file("test-results/endpoints-$task.json")

  systemProperty("testbed.task", task)
  systemProperty("testbed.endpoints.report", report.get().asFile.absolutePath)
  outputs.file(report)

  // What OkHttp's handshake offered, recorded rather than asserted. Same one-file-per-task rule
  // as the endpoint report, and for the same reason.
  val clientHello = layout.buildDirectory.file("test-results/clienthello-$task.json")
  systemProperty("testbed.clienthello.report", clientHello.get().asFile.absolutePath)
  outputs.file(clientHello)

  // What each DoH resolver said about each name. A record rather than a result, for the same
  // reason and by the same one-file-per-task rule.
  val dohMatrix = layout.buildDirectory.file("test-results/doh-matrix-$task.json")
  systemProperty("testbed.doh.report", dohMatrix.get().asFile.absolutePath)
  outputs.file(dohMatrix)

  // Which origins offer HTTP/3, and what OkHttp used instead. Same rule again.
  val altSvc = layout.buildDirectory.file("test-results/altsvc-$task.json")
  systemProperty("testbed.altsvc.report", altSvc.get().asFile.absolutePath)
  outputs.file(altSvc)

  // Revocation, pinning and CT, per platform. Recorded rather than asserted, so the file is the
  // deliverable rather than a by-product; same one-file-per-task rule.
  val tlsPolicy = layout.buildDirectory.file("test-results/tlspolicy-$task.json")
  systemProperty("testbed.tlspolicy.report", tlsPolicy.get().asFile.absolutePath)
  outputs.file(tlsPolicy)

  // The ECHConfigList used for each attempt, including a server-provided retry config.
  val echResults = layout.buildDirectory.file("test-results/ech-$task.json")
  systemProperty("testbed.ech.report", echResults.get().asFile.absolutePath)
  outputs.file(echResults)
}

val networkTest =
  tasks.register<Test>("networkTest") {
    group = "verification"
    description = "Runs the suites that call public servers. Records failures without failing the build."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    exclude(
      "**/$echTestPattern.class",
      "**/$echConscryptTestPattern.class",
      "**/$echClientHelloTestPattern.class",
    )

    reportEndpointsTo("networkTest")
    ignoreFailures = true
  }

// EchTest stays its own task rather than joining networkTest, for two reasons that have
// nothing to do with gating — both tasks already report. It is the one suite left out of the
// build entirely below 5.5.0, so it needs its own `enabled`; and its failures say something
// different from the rest of the module. A networkTest failure is usually about a server or
// the route to it, where an EchTest failure is about the JDK's TLS stack being unable to
// encrypt a client hello. Separate tasks keep those apart on the status page.
val echTest =
  tasks.register<Test>("echTest") {
    group = "verification"
    description = "Reports how much of ECH works on the JVM. Records failures without failing the build."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    include("**/$echTestPattern.class")

    reportEndpointsTo("echTest")
    enabled = supportsEch && hasConscrypt
    ignoreFailures = true

    doFirst {
      logger.lifecycle("Testing ECH against OkHttp $okhttpVersion on each platform")
    }
  }

// The same servers, through Conscrypt rather than the JDK. Its own task for the same reason
// echTest is: its result answers a different question. echTest says whether OkHttp can do ECH as
// shipped, which on the JVM it cannot; this says whether the missing piece is the TLS stack and
// nothing else. Reading them together is the point, so they report side by side.
val echConscryptTest =
  tasks.register<Test>("echConscryptTest") {
    group = "verification"
    description = "Reports whether ECH works on the JVM with a Conscrypt that supports it."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    // EchClientHelloTest runs here rather than under networkTest despite calling nobody: it
    // answers the same question as the rest of this task and needs the same Conscrypt, and
    // reading a "the servers agree" result next to a "the bytes are right" one is the point.
    include("**/$echConscryptTestPattern.class", "**/$echClientHelloTestPattern.class")

    reportEndpointsTo("echConscryptTest")
    enabled = supportsEch && hasConscrypt
    ignoreFailures = true

    doFirst {
      logger.lifecycle("Testing ECH against OkHttp $okhttpVersion on Conscrypt")
    }
  }

if (!supportsEch) {
  logger.lifecycle("Skipping EchTest: OkHttp $okhttpVersion predates the ECH API")
}

if (!hasConscrypt) {
  logger.lifecycle("Skipping EchConscryptTest: no Conscrypt build. Run conscrypt/fetch-conscrypt.sh.")
}

tasks.check {
  dependsOn(networkTest, echTest, echConscryptTest)
}

dependencies {
  testImplementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
  testImplementation("com.squareup.okhttp3:okhttp-tls:$okhttpVersion")
  testImplementation("com.squareup.okhttp3:okhttp-dnsoverhttps:$okhttpVersion")

  // Absent unless someone staged it; `hasConscrypt` leaves EchConscryptTest out when it is.
  testImplementation(conscryptJars)

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.assertk)
}
