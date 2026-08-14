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

// An image the suites and the deployable compose stack both run has to be pinned in two
// files that can't read each other — Gradle's version catalogue and a compose YAML. Rather
// than trust them to stay in step, check it: the catalogue is the source, and this fails if
// the compose file has drifted. Same reasoning as checkPublicApiOnly — a convention nobody
// enforces is a convention until the first time it matters.
val pinnedImages =
  mapOf(
    "ghcr.io/mccutchen/go-httpbin" to libs.versions.gohttpbin.get(),
  )

val composeFile = layout.projectDirectory.file("test-server/docker-compose.yml")

val checkImagePins =
  tasks.register("checkImagePins") {
    group = "verification"
    description = "Fails if a pinned image tag in docker-compose.yml has drifted from the version catalogue."

    inputs.file(composeFile).withPropertyName("compose").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("pinnedImages", pinnedImages)
    outputs.upToDateWhen { true }

    doLast {
      val lines = composeFile.asFile.readLines()

      val offences =
        pinnedImages.flatMap { (image, expected) ->
          val found =
            lines
              .map(String::trim)
              .filter { it.startsWith("image:") && it.contains("$image:") }
              .map { it.substringAfterLast("$image:") }

          when {
            found.isEmpty() -> listOf("$image is not pinned in ${composeFile.asFile.name} at all")
            else ->
              found
                .filter { it != expected }
                .map { "$image is $it in ${composeFile.asFile.name}, but $expected in libs.versions.toml" }
          }
        }

      if (offences.isNotEmpty()) {
        throw GradleException(
          offences.joinToString(
            prefix = "Image pins have drifted. Update the compose file, or the catalogue:\n  ",
            separator = "\n  ",
          ),
        )
      }
    }
  }

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

    // Cheap, and it runs where it matters: a suite about to start a pinned image is exactly
    // when a drifted pin is worth hearing about.
    dependsOn(checkImagePins)

    useJUnitPlatform()

    // Report pinned carrier threads rather than silently passing, for the Loom tests.
    jvmArgs("-Djdk.tracePinnedThreads=short")

    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
  }
}
