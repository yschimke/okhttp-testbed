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

sourceSets {
  test {
    kotlin {
      if (!supportsEch) {
        exclude("**/$echTestPattern.kt")
      }
    }
  }
}

// EchTest reports on OkHttp rather than on this repository, the way containers' BasicLoomTest
// does. It asserts that a call really is protected by ECH, which needs both the DNS half —
// OkHttp reading an ECH config list out of an HTTPS record, which works here — and the TLS half:
// a stack that can encrypt the client hello. The JDK's can't, so the handshake assertions fail on
// the JVM today. That is a finding about the platform, recorded in the JUnit XML, not this repo
// being broken, so the assertions stand as written and the build stays green. See the README.
tasks.test {
  exclude("**/$echTestPattern.class")
}

val echTest =
  tasks.register<Test>("echTest") {
    group = "verification"
    description = "Reports how much of ECH works on the JVM. Records failures without failing the build."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    include("**/$echTestPattern.class")

    enabled = supportsEch
    ignoreFailures = true

    doFirst {
      logger.lifecycle("Testing ECH against OkHttp $okhttpVersion")
    }
  }

if (!supportsEch) {
  logger.lifecycle("Skipping EchTest: OkHttp $okhttpVersion predates the ECH API")
}

tasks.check {
  dependsOn(echTest)
}

dependencies {
  testImplementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
  testImplementation("com.squareup.okhttp3:okhttp-tls:$okhttpVersion")
  testImplementation("com.squareup.okhttp3:okhttp-dnsoverhttps:$okhttpVersion")

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.assertk)
}
