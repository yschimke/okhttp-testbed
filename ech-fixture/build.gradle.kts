import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
  alias(libs.plugins.kotlin.jvm)
}

// Not a suite: this module holds no tests and never touches OkHttp. It builds and runs the
// containers the `android-ech` suite talks to, on the host, because the device that runs
// those tests has no Docker of its own.
dependencies {
  implementation(libs.testcontainers)
}

tasks.register<JavaExec>("runEchFixture") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Starts the DoH and HTTPS containers the Android ECH suite runs against."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "okhttp.testbed.ech.EchFixtureService"
}
