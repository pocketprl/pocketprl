plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The version code a *regular* build of this release carries. A shifted build
// (rollback or back, reissued above the primary band) ships a higher
// versionCode than this, which is how the app detects it at runtime.
val pocketprlNormalVersionCode: Long =
    (project.findProperty("pocketprl.normalVersionCode") as String?)?.toLongOrNull()
        ?: ((project.findProperty("pocketprl.versionCode") as String?)?.toIntOrNull() ?: 15).toLong()

android {
    namespace = "dev.pocketprl"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pocketprl.wallet"
        minSdk = 28
        targetSdk = 37
        // Reissued rollback/back builds take their version code from the lane
        // bands (250000 - e / 300000 + e) so an older release can be installed
        // over a newer one. See docs/VERSIONING.md. The literal is only a fallback
        // for builds that do not resolve the property.
        versionCode = (project.findProperty("pocketprl.versionCode") as String?)?.toIntOrNull() ?: 15
        versionName = "2.5.4"
        buildConfigField("long", "NORMAL_VERSION_CODE", "${pocketprlNormalVersionCode}L")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Release signing comes from the environment. It is never silently replaced
    // with the debug key: without a keystore the release APK is left UNSIGNED
    // (not installable, so it cannot be mistaken for a shippable build). For a
    // throwaway local release, set POCKETPRL_ALLOW_DEBUG_SIGNING=1.
    signingConfigs {
        val keystore = System.getenv("POCKETPRL_KEYSTORE")
        val allowDebug = System.getenv("POCKETPRL_ALLOW_DEBUG_SIGNING") == "1"
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("POCKETPRL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKETPRL_KEY_ALIAS")
                keyPassword = System.getenv("POCKETPRL_KEY_PASSWORD")
            }
        } else if (allowDebug) {
            logger.warn("POCKETPRL_ALLOW_DEBUG_SIGNING=1: release APK will be signed with the DEBUG key and must not be distributed.")
            create("release") {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        } else {
            logger.warn("POCKETPRL_KEYSTORE not set: release APK will be UNSIGNED and must not be distributed. Set POCKETPRL_KEYSTORE or POCKETPRL_ALLOW_DEBUG_SIGNING=1.")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Pin the bytecode target explicitly. Without this the Kotlin plugin's default
// can change between versions, which would move the APK hash between builds.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.core.ktx)
    implementation(libs.biometric)
    implementation(libs.fragment)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.secp256k1.kmp)
    implementation(libs.secp256k1.jni.android)

    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.secp256k1.jni.jvm)
}
