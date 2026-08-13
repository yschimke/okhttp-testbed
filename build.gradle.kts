import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.junit5) apply false
}

val testJavaVersion =
  providers
    .gradleProperty("testJavaVersion")
    .map(String::toInt)
    .getOrElse(21)

// Packages a suite must not touch. The testbed exists to test OkHttp the way its users
// get it — the published artifact's public API. Anything below reaches past that, either
// into OkHttp's internals or into test fixtures that are never published, and would make
// a suite unable to run against an arbitrary release.
val forbiddenImports =
  listOf(
    "okhttp3.internal",
    "okhttp3.testing",
    "mockwebserver3.internal",
    "okio.internal",
  )

subprojects {
  // Suites bring their own Kotlin plugin: `android-ech` is an Android module and can't
  // share one with the JVM suites. Everything below is common to all of them, so it
  // reacts to whichever plugin the suite applied rather than applying one from here.
  plugins.withId("org.jetbrains.kotlin.jvm") {
    configure<JavaPluginExtension> {
      toolchain {
        languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
      }
    }
  }

  val sources = fileTree("src") { include("**/*.kt", "**/*.java") }
  val report = layout.buildDirectory.file("reports/public-api-only.txt")

  val checkPublicApiOnly =
    tasks.register("checkPublicApiOnly") {
      group = "verification"
      description = "Fails if a suite imports OkHttp internals or unpublished test fixtures."

      inputs.files(sources).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
      outputs.file(report)

      doLast {
        val offences =
          sources.files.sorted().flatMap { file ->
            file
              .readLines()
              .withIndex()
              .filter { (_, line) ->
                line.startsWith("import ") && forbiddenImports.any { line.removePrefix("import ").startsWith("$it.") }
              }.map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
          }

        report.get().asFile.apply {
          parentFile.mkdirs()
          writeText(offences.joinToString("\n"))
        }

        if (offences.isNotEmpty()) {
          throw GradleException(
            offences.joinToString(
              prefix = "Tests must use OkHttp's public API only. Forbidden imports:\n  ",
              separator = "\n  ",
            ),
          )
        }
      }
    }

  plugins.withType<LifecycleBasePlugin> {
    tasks.named("check") {
      dependsOn(checkPublicApiOnly)
    }
  }

  tasks.withType<Test>().configureEach {
    dependsOn(checkPublicApiOnly)

    useJUnitPlatform()

    // Report pinned carrier threads rather than silently passing, for the Loom tests.
    jvmArgs("-Djdk.tracePinnedThreads=short")

    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
  }
}
