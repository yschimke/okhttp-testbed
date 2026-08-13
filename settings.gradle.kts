rootProject.name = "okhttp-testbed"

dependencyResolutionManagement {
  repositories {
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
