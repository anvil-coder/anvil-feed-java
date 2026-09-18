plugins {
    `java-library`
}

subprojects {
    apply(plugin = "java-library")

    group = "tech.anvil-coder"
    version = "0.1.0"

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // C18: the supported major is a fixed number in exactly one place, and it is
            // gradle/libs.versions.toml. Running on anything else is a build failure here rather than a
            // NoSuchMethodError at a customer's.
            languageVersion.set(JavaLanguageVersion.of(
                rootProject.extensions.getByType<VersionCatalogsExtension>()
                    .named("libs").findVersion("java").get().requiredVersion
            ))
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
