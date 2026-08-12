plugins {
  alias(libs.plugins.kotlin.jvm) apply false
}

val testJavaVersion =
  providers
    .gradleProperty("testJavaVersion")
    .map(String::toInt)
    .getOrElse(21)

subprojects {
  apply(plugin = "org.jetbrains.kotlin.jvm")

  configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
    }
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Report pinned carrier threads rather than silently passing, for the Loom tests.
    jvmArgs("-Djdk.tracePinnedThreads=short")

    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
  }
}
