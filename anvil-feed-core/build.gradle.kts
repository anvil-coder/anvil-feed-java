dependencies {
    // X5: no Spring, no Quarkus, no framework of any kind. Z2: two runtime dependencies, and slf4j-api
    // is an interface package. The HTTP client is the JDK's (O11) and there is no second one.
    api(libs.jackson.databind)
    compileOnly(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.slf4j.api)
    // TEST ONLY. The serialiser is checked against schema/v1.0/event.schema.json itself, not against a
    // hand-written reading of it; the validator never ships to a host.
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Review finding 10: `sdk.version` on the wire and the published Maven coordinate must be ONE number.
// The coordinate comes from gradle/libs.versions.toml (C18) and this generates the constant from it, so
// there is nothing left to keep in sync by hand.
val generatedVersionDir = layout.buildDirectory.dir("generated/sources/sdkversion/java/main")
val generateSdkVersion by tasks.registering {
    val target = generatedVersionDir.map { it.file("tech/anvilcoder/feed/SdkVersion.java") }
    val value = project.version.toString()
    inputs.property("sdkVersion", value)
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package tech.anvilcoder.feed;

            /**
             * Generated from {@code gradle/libs.versions.toml} at build time — do not edit, and do not
             * spell this number anywhere else (C18).
             */
            final class SdkVersion {

                static final String VALUE = "$value";

                private SdkVersion() {
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.named("main") {
    java.srcDir(generatedVersionDir)
}

tasks.named("compileJava") {
    dependsOn(generateSdkVersion)
}

tasks.named("sourcesJar") {
    dependsOn(generateSdkVersion)
}

tasks.test {
    // The conformance fixtures live above the Gradle root; the tests locate them from this property
    // rather than from a relative path that changes with the working directory.
    systemProperty("anvilfeed.conformance", rootProject.projectDir.parentFile.resolve("conformance").absolutePath)
    systemProperty("anvilfeed.schema", rootProject.projectDir.parentFile.resolve("schema").absolutePath)
    // Review finding 10: the guard that the generated constant and the published coordinate are one number.
    systemProperty("anvilfeed.version", project.version.toString())
}
