plugins {
  // AGP 9 brings Kotlin support with it — applying the Kotlin Android plugin here fails.
  alias(libs.plugins.android.library)
  alias(libs.plugins.android.junit5)
}

// The version of OkHttp under test. This suite defaults to the snapshot rather than to the
// release the other suites pin, because ECH needs `DnsOverHttps.includeServiceMetadata` and
// no release has it yet. Override the same way as everywhere else:
//   ./gradlew android-ech:connectedDebugAndroidTest -PokhttpVersion=5.5.0-SNAPSHOT
val okhttpVersion =
  providers
    .gradleProperty("okhttpVersion")
    .getOrElse(libs.versions.ech.okhttp.get())

// Snapshots are republished under the same name, and Gradle caches a changing module for 24
// hours, so without this a daily run can test yesterday's build. Releases are immutable.
if (okhttpVersion.endsWith("-SNAPSHOT")) {
  configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
  }
}

android {
  namespace = "okhttp.testbed.android.ech"

  compileSdk {
    // ECH arrived in Android 16 QPR2 / API 37: `android.net.ssl.EchConfigList` is what
    // OkHttp's Android platform hands the config list to.
    version = release(37)
  }

  defaultConfig {
    minSdk = 21

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments +=
      mapOf(
        // The suite is JUnit 5, as the JVM suites are.
        "runnerBuilder" to "de.mannodermaus.junit5.AndroidJUnit5Builder",
      )
  }

  compileOptions {
    sourceCompatibility(JavaVersion.VERSION_11)
    targetCompatibility(JavaVersion.VERSION_11)
  }

  testOptions {
    targetSdk = 37
  }
}

dependencies {
  androidTestImplementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
  androidTestImplementation("com.squareup.okhttp3:okhttp-dnsoverhttps:$okhttpVersion")

  // Not optional, despite nothing here naming it. On Android the public suffix list is read
  // from `assets/PublicSuffixDatabase.list`, which only this artifact ships; without it every
  // `DnsOverHttps` query throws from `isPrivateHost` before a connection is attempted, and the
  // whole suite fails on something that has nothing to do with ECH.
  androidTestImplementation("com.squareup.okhttp3:okhttp-android:$okhttpVersion")

  androidTestImplementation(libs.assertk)
  androidTestImplementation(libs.junit.jupiter.api)
  androidTestImplementation(libs.junit5android.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestRuntimeOnly(libs.junit5android.runner)
}

// `check` doesn't run instrumentation tests, so the public-API check has to be wired to the
// task that does — otherwise this suite could reach into okhttp3.internal unnoticed.
tasks
  .matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }
  .configureEach {
    dependsOn("checkPublicApiOnly")
  }
