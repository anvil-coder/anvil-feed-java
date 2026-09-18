dependencies {
    api(project(":anvil-feed-core"))
    implementation(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.slf4j.api)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    // The root build compiles every module with `-Xlint:all -Werror`, and that stays. `processing` is the
    // one lint in that set which is about the BUILD rather than about the code: spring-boot-configuration-
    // processor READS Spring's annotations without claiming them, so javac reports every one of them as
    // "no processor claimed" and -Werror turns a correct build into a failed one. Nothing about the source
    // is being warned about here, and every other lint — including -Xlint:try, which is what the core's
    // RequestScope javadoc warns this module about — is still on and still fatal.
    options.compilerArgs.add("-Xlint:-processing")
}

tasks.test {
    // The contract file lives above the Gradle root. AnvilFeedEnvironmentTest reads the `env` block out
    // of it rather than restating the five variable names, exactly as the core's ConfigParityTest does —
    // a name that moves in the file has to move here too, or a test goes red.
    systemProperty(
        "anvilfeed.conformance",
        rootProject.projectDir.parentFile.resolve("conformance").absolutePath,
    )
}
