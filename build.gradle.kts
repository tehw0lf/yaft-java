plugins {
    `java-library`
}

java {
    // No auto-provisioning: a missing JDK 25 should fail the build, not
    // trigger a download. CI installs it through the workflow's java_version.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // The library itself has no runtime dependencies. Jackson only reads the
    // conformance case files.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jackson.databind)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all", true)
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "de.tehwolf.yaft")
    }
}

val conformanceDir = layout.projectDirectory.dir("src/test/conformance")

// The suite pinned in conformance.lock, fetched and checksum-verified before
// every test run. Up to date as long as the lock file and the unpacked cases
// are unchanged, so a local rerun does not hit the network.
val fetchConformance = tasks.register<Exec>("fetchConformance") {
    description = "Fetches the YaFT conformance suite pinned in conformance.lock."
    group = "verification"
    inputs.file("conformance.lock")
    inputs.file("scripts/fetch-conformance.sh")
    outputs.dir(conformanceDir)
    commandLine("bash", "scripts/fetch-conformance.sh", conformanceDir.asFile.path)
}

tasks.test {
    dependsOn(fetchConformance)
    useJUnitPlatform()
    inputs.dir(conformanceDir)
    systemProperty("yaft.conformance.dir", conformanceDir.asFile.path)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
