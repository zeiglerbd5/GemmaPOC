plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zeiglerbd5.companion.gemmapoc"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Play Store identity — permanent after first Play upload. Differs
        // from the source package/namespace (kept as the original POC name
        // to avoid a pointless mass refactor); only this ID is public.
        applicationId = "ai.stillwaterai.onhand"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Play upload key. Credentials live in ~/.gradle/gradle.properties
    // (ONHAND_UPLOAD_*), never in the repo. On machines without them the
    // release build simply produces an unsigned bundle — debug builds are
    // unaffected. Play App Signing holds the real signing key; this
    // keystore only authenticates uploads.
    val uploadStoreFile = providers.gradleProperty("ONHAND_UPLOAD_STORE_FILE").orNull
    if (uploadStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(uploadStoreFile)
                storePassword = providers.gradleProperty("ONHAND_UPLOAD_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("ONHAND_UPLOAD_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("ONHAND_UPLOAD_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // android.util.Log (and other framework stubs) return defaults
            // instead of throwing "not mocked" in plain JVM unit tests — the
            // ChatStore search loop logs breadcrumbs via Log.i.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.litertlm.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}