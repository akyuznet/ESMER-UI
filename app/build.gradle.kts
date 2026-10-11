// Imported rather than fully qualified: inside a Gradle Kotlin DSL script `java`
// resolves to the Java plugin extension, which shadows the package of the same name.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing, read from a properties file that is deliberately not committed.
// Anyone cloning this repo builds unsigned release APKs and signs with their own
// key; only this machine holds the one the published releases are signed with.
val signingProps = rootProject.file("keystore.properties").let { file ->
    if (file.exists()) Properties().apply { file.inputStream().use { load(it) } } else null
}

android {
    namespace = "com.example.esmerui"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.esmerui"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // The debug build is the one that runs on the car, so it is the one that is
        // shrunk: R8 strips the unused nine tenths of Compose and Material out of
        // the dex, which is most of what the process kept mapped. Still debuggable,
        // so `run-as` keeps working for backing up the bar layout before installs.
        // The rules live in src/main/keepRules, the source folder AGP reads them from.
        debug {
            optimization {
                enable = true
            }
        }
        release {
            optimization {
                enable = true
            }
            // Falls back to unsigned when the key is absent, so a clone still builds.
            signingProps?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.dadb)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}