plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

java {
    // No auto-provisioning: a missing JDK 25 should fail the build, not
    // trigger a download. CI installs it through the workflow's java_version.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
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

// Sources and javadoc jars come from the publish plugin, which Maven Central
// requires. Signing and upload credentials are read from ORG_GRADLE_PROJECT_*
// variables in CI (tehw0lf/workflows publish-maven-central.yml) and never
// stored here.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // Signed whenever a key is present, which CI always provides (the
    // workflow refuses to run without one, and Central rejects unsigned
    // bundles). Without a key, publishToMavenLocal still works, so a
    // playground can build against an unreleased version.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name = "YaFT for Java"
        description = "Yet another Feature Toggle: feature toggles for classes and methods, " +
            "conformant with yaft-conformance."
        url = "https://github.com/tehw0lf/yaft-java"
        inceptionYear = "2026"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "tehw0lf"
                name = "Robert Weyres"
                url = "https://github.com/tehw0lf"
            }
        }
        scm {
            url = "https://github.com/tehw0lf/yaft-java"
            connection = "scm:git:https://github.com/tehw0lf/yaft-java.git"
            developerConnection = "scm:git:ssh://git@github.com/tehw0lf/yaft-java.git"
        }
    }
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
