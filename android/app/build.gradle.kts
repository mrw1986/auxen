plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.auxen"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.auxen"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Tidal API credentials are NOT checked into the repo. Put them in
        // ~/.gradle/gradle.properties or android/local.properties as
        //   auxen.tidalClientId=...
        //   auxen.tidalClientSecret=...
        // (the same client id/secret pair the desktop app's tidalapi uses).
        val tidalClientId = (project.findProperty("auxen.tidalClientId") as? String) ?: ""
        val tidalClientSecret = (project.findProperty("auxen.tidalClientSecret") as? String) ?: ""
        buildConfigField("String", "TIDAL_CLIENT_ID", "\"$tidalClientId\"")
        buildConfigField("String", "TIDAL_CLIENT_SECRET", "\"$tidalClientSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
}
