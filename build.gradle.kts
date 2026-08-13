plugins {
  alias(libs.plugins.kotlin.jvm) apply false
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

// The version of OkHttp under test, shared by every suite. Override to check a release
// candidate or a snapshot:
//   ./gradlew test -PokhttpVersion=5.5.0-SNAPSHOT
val okhttpVersion =
  providers
    .gradleProperty("okhttpVersion")
    .getOrElse(libs.versions.okhttp.get())

subprojects {
  apply(plugin = "org.jetbrains.kotlin.jvm")

  extra["okhttpVersion"] = okhttpVersion

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

  configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
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

  tasks.named("check") {
    dependsOn(checkPublicApiOnly)
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
