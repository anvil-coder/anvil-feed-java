plugins {
    // Lets the toolchain in build.gradle.kts provision a JDK 25 on a machine that only has 22 on its PATH.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "anvil-feed"

dependencyResolutionManagement {
    // One place for every module. gradle/libs.versions.toml is picked up by convention and is the only
    // place a version number is written (C18).
    repositories {
        mavenCentral()
    }
}

include("anvil-feed-core")
include("anvil-feed-spring-boot-starter")
