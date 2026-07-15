plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
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
        // ~/.gradle/gradle.properties or android/local.properties.
        //   auxen.tidalClientId / auxen.tidalClientSecret
        //     = the INTERNAL tidalapi client (same pair the desktop app uses);
        //       drives TidalAuth's device-code login against api.tidal.com/v1.
        //   auxen.tidalOfficialClientId
        //     = the OFFICIAL developer app "Auxen" (developer.tidal.com); drives
        //       the official Open API PKCE login (TidalOfficialSession). It is a
        //       DIFFERENT client id from the internal one — using the internal id
        //       against login.tidal.com/authorize returns error 11102. PKCE is a
        //       public-client flow, so no official secret is sent (none needed here).
        val tidalClientId = (project.findProperty("auxen.tidalClientId") as? String) ?: ""
        val tidalClientSecret = (project.findProperty("auxen.tidalClientSecret") as? String) ?: ""
        val tidalOfficialClientId = (project.findProperty("auxen.tidalOfficialClientId") as? String) ?: ""
        buildConfigField("String", "TIDAL_CLIENT_ID", "\"$tidalClientId\"")
        buildConfigField("String", "TIDAL_CLIENT_SECRET", "\"$tidalClientSecret\"")
        buildConfigField("String", "TIDAL_OFFICIAL_CLIENT_ID", "\"$tidalOfficialClientId\"")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Unit tests run on the debug variant only: the Compose test activity
// (ui-test-manifest) is debug-scoped, and the release unit-test variant
// would duplicate identical JVM tests minus that infra.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as? com.android.build.api.variant.HasUnitTestBuilder)?.enableUnitTest = false
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
    // Adaptive navigation: WindowWidthSizeClass drives the bottom-bar (compact)
    // vs. navigation-rail (medium/expanded, i.e. tablet/unfolded foldable)
    // switch in MainActivity. BOM-managed alongside material3 itself.
    implementation(libs.androidx.compose.material3.windowSizeClass)
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
    implementation(libs.androidx.core.splashscreen)
    // Official Tidal API PKCE login (Tidal official-API migration, Task 1):
    // Custom Tabs, not an embedded WebView, per RFC 8252 -- see
    // TidalOfficialAuth.kt's KDoc. Explicitly named as the roll-your-own
    // mechanism in the task brief (vs. adopting com.tidal.sdk:auth, which
    // pulls in Dagger + Retrofit -- see the Task 1 report for why that was
    // rejected).
    implementation(libs.androidx.browser)

    // Animated drag-reorder for the Queue screen's up-next list (github.com/Calvin-LL/Reorderable).
    // 2.5.1 is the latest stable 2.x; it targets Compose 1.7.x, matching this
    // module's Compose BOM (2024.12.01). minSdk 21, below the app's 26.
    implementation(libs.reorderable)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Compose UI testing on the JVM (Robolectric). ui-test-junit4 and
    // ui-test-manifest carry no explicit version — the Compose BOM must be
    // applied to the test/debug classpaths for them to resolve.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
