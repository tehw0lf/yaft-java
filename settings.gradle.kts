pluginManagement {
    repositories {
        // The Plugin Portal's marker for com.vanniktech.maven.publish stops at
        // 0.13.0; current releases are only resolvable from Maven Central.
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "yaft"
