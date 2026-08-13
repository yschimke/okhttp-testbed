plugins {
  alias(libs.plugins.kotlin.jvm)
}

// The version of OkHttp under test. Override to check a release candidate or a snapshot:
//   ./gradlew network:networkTest -PokhttpVersion=5.5.0-SNAPSHOT
val okhttpVersion =
  providers
    .gradleProperty("okhttpVersion")
    .getOrElse(libs.versions.okhttp.get())

// A snapshot has to be re-resolved every run — see the same note in containers/build.gradle.kts.
if (okhttpVersion.endsWith("-SNAPSHOT")) {
  configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
  }
}

// Every test in this module reaches a server someone else operates, so every one of them
// can fail for reasons that are nothing to do with OkHttp or with this repository: a rate
// limit, a renewed certificate, a runner behind a captive resolver. That is the loomTest
// situation — a result worth recording, not a build worth failing — so the whole module
// runs under `networkTest` with ignoreFailures, and the plain `test` task stays empty.
tasks.test {
  enabled = false
}

val networkTest =
  tasks.register<Test>("networkTest") {
    group = "verification"
    description = "Runs the suites that reach public servers. Records failures without failing the build."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    ignoreFailures = true
  }

tasks.check {
  dependsOn(networkTest)
}

dependencies {
  testImplementation("com.squareup.okhttp3:okhttp:$okhttpVersion")

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.assertk)
}
