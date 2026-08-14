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

val networkTest =
  tasks.register<Test>("networkTest") {
    group = "verification"
    description = "Runs the suites that call public servers. Records failures without failing the build."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    exclude("**/$echTestPattern.class")

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
  dependsOn(networkTest, echTest)
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
