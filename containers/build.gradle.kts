// The version of OkHttp under test. Override to check a release candidate or a snapshot:
//   ./gradlew test -PokhttpVersion=5.5.0-SNAPSHOT
val okhttpVersion =
  providers
    .gradleProperty("okhttpVersion")
    .getOrElse(libs.versions.okhttp.get())

tasks.withType<Test>().configureEach {
  // Single source of truth for the MockServer version: the container image tag is
  // derived from it, so the client and the server can't drift apart.
  systemProperty("mockserver.version", libs.versions.mockserver.get())
}

dependencies {
  testImplementation("com.squareup.okhttp3:okhttp:$okhttpVersion")

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
