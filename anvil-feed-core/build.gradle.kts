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

tasks.test {
    // The conformance fixtures live above the Gradle root; the tests locate them from this property
    // rather than from a relative path that changes with the working directory.
    systemProperty("anvilfeed.conformance", rootProject.projectDir.parentFile.resolve("conformance").absolutePath)
    systemProperty("anvilfeed.schema", rootProject.projectDir.parentFile.resolve("schema").absolutePath)
}
