rootProject.name = "okhttp-testbed"

pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
      mavenContent {
        snapshotsOnly()
      }
    }
  }
}

include(":containers")
include(":network")
include(":ech-fixture")
include(":android-ech")
