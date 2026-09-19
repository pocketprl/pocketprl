// Pin the Kotlin Gradle Plugin so the Compose and serialization compiler plugins match it.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Reproducible archives where Gradle builds them: no wall-clock timestamps and
// a stable entry order, so the same inputs give the same bytes. (APK packaging
// is done by AGP, not AbstractArchiveTask, and still needs verification.)
tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
