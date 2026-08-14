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

tasks.withType<Test>().configureEach {
  // Single source of truth for the MockServer version: the container image tag is
  // derived from it, so the client and the server can't drift apart.
  systemProperty("mockserver.version", libs.versions.mockserver.get())

  // Same rule for a different reason. Nothing on the classpath has to agree with go-httpbin,
  // but test-server's compose stack runs the same image for a deployment — so the tag lives in
  // the catalogue and both read it from there. checkImagePins holds the compose file to it.
  systemProperty("gohttpbin.version", libs.versions.gohttpbin.get())

  // BadChainTest builds test-server's image rather than pulling one, so it needs the build
  // context. Supplied here rather than reached for with a relative path, which would depend on
  // the working directory the tests happen to run in.
  systemProperty("testbed.testServerDir", rootProject.layout.projectDirectory.dir("test-server").asFile.absolutePath)
}

// BasicLoomTest reports on OkHttp rather than on this repository: it asserts that no
// virtual thread pins its carrier, which today it does — Http2Connection.newStream holds
// a monitor across Http2Writer.flush's blocking write. That is a true finding about the
// published artifact on JDK 21 (JEP 491 removes the pinning on 24+), not a broken test,
// so the assertion stands as written and the result is recorded in the JUnit XML. It
// just doesn't fail the build: a finding about OkHttp shouldn't read as this repo being
// broken. Every other suite stays fatal, which is what caught the MockServer version
// mismatch.
val loomTestPattern = "**/BasicLoomTest.class"

// HostileRetryTest asks how many times OkHttp sends a request the server killed under it. The
// answer is a fact about OkHttp's retry policy, not a defect here, so it records rather than
// gates — the same category as loomTest. HostileResponseTest, which asserts only that a
// malformed response fails at all, stays fatal.
val hostileTestPattern = "**/HostileRetryTest.class"

// MockServer is the one thing on this classpath that isn't Java 8 bytecode: the client's own
// classes are compiled for 17, so on an older test JDK every suite that touches it dies during
// class resolution — and it takes the whole task with it, because JUnit resolves the classes it
// found before it runs any of them, so filtering by name doesn't help.
//
// The alternative to excluding them is not running this module below 17 at all, which would
// give up the suites that have nothing to do with MockServer. go-httpbin, the bad chains and
// the hostile responses all run against this repository's own containers, and how OkHttp
// handles them on Java 8 is a question worth an answer.
val mockServerTestPattern =
  listOf(
    "**/BasicMockServerTest.class",
    "**/BasicProxyTest.class",
    "**/SocksProxyTest.class",
  )

// `-PtestJavaVersion`, as the root build reads it. BasicLoomTest is already excluded from
// `test` and is `@EnabledForJreRange(min = JAVA_21)` besides, so it needs nothing here.
val testJavaVersion =
  providers
    .gradleProperty("testJavaVersion")
    .map(String::toInt)
    .getOrElse(21)

val mockServerRuns = testJavaVersion >= 17

tasks.test {
  exclude(loomTestPattern, hostileTestPattern)
  if (!mockServerRuns) {
    exclude(mockServerTestPattern)
    doFirst {
      logger.lifecycle("Skipping the MockServer suites: its client is Java 17 bytecode, and this run is on $testJavaVersion")
    }
  }
}

val loomTest = tasks.register<Test>("loomTest") {
  group = "verification"
  description = "Reports carrier-thread pinning in OkHttp. Records failures without failing the build."

  val testSourceSet = sourceSets.test.get()
  testClassesDirs = testSourceSet.output.classesDirs
  classpath = testSourceSet.runtimeClasspath
  include(loomTestPattern)

  // Virtual threads are the whole subject, and BasicLoomTest says so itself with
  // `@EnabledForJreRange(min = JAVA_21)`. Below that the task can only produce an empty run —
  // or worse, since the class it would load reaches MockServer, a resolution failure dressed
  // up as a Loom result.
  enabled = testJavaVersion >= 21

  ignoreFailures = true
}

val hostileTest =
  tasks.register<Test>("hostileTest") {
    group = "verification"
    description = "Reports whether OkHttp retries a request the server killed. Records failures without failing the build."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    include(hostileTestPattern)

    ignoreFailures = true
  }

tasks.check {
  dependsOn(loomTest, hostileTest)
}

dependencies {
  testImplementation("com.squareup.okhttp3:okhttp:$okhttpVersion")

  // A real trust manager from the fixture CA, without a KeyStore dance and without weakening
  // verification. okhttp-tls is published, so this stays inside the public-API-only rule.
  testImplementation("com.squareup.okhttp3:okhttp-tls:$okhttpVersion")

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.assertk)
  testImplementation(libs.testcontainers)
  testImplementation(libs.testcontainers.junit5)
  testImplementation(libs.mockserver)
  testImplementation(libs.mockserver.client)
}
