plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bojustudio.pingd.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bojustudio.pingd"
        minSdk = 34
        targetSdk = 36
        // VERSION_CODE and VERSION_NAME are injected by CI (date-based code that always
        // increases; tag-derived name). Fall back to static values for local builds.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only created when signing env vars are present (CI release/manual dispatch).
        // Local builds and CI unit-test runs produce unsigned release APKs as before.
        val storeFilePath = System.getenv("STORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
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
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Stage 3: preferences storage (spec's Implementation Baseline names Jetpack
    // DataStore, not SharedPreferences) and the coroutines/Flow API it's built on.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    // Contributes a test-only launcher activity into the debug manifest merge, which
    // both `testDebugUnitTest` (Robolectric) and `connectedDebugAndroidTest` read from —
    // must be debugImplementation, not testImplementation, or manifest merging skips it
    // and createComposeRule() can't resolve an activity to host content in.
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
