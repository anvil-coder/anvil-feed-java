plugins {
    `java-library`
}

/** The one place a version number is read from: gradle/libs.versions.toml (C18). */
fun catalogVersion(name: String): String =
    rootProject.extensions.getByType<VersionCatalogsExtension>()
        .named("libs").findVersion(name).get().requiredVersion

subprojects {
    apply(plugin = "java-library")

    group = "tech.anvil-coder"
    // C18 again, and review finding 10: the published coordinate and the `sdk.version` a customer reads
    // off a support ticket were two independent literals and would have drifted at the first release.
    version = catalogVersion("sdkVersion")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // C18: the supported major is a fixed number in exactly one place, and it is
            // gradle/libs.versions.toml. Running on anything else is a build failure here rather than a
            // NoSuchMethodError at a customer's.
            languageVersion.set(JavaLanguageVersion.of(catalogVersion("java")))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed", "skipped") }
    }
}
