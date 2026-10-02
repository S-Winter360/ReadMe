plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.readme.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.readme.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val storeFilePath = System.getenv("README_KEYSTORE_FILE")
                ?: (findProperty("readmeKeystoreFile") as? String)
            val storePass = System.getenv("README_KEYSTORE_PASSWORD")
                ?: (findProperty("readmeKeystorePassword") as? String)
            val keyAl = System.getenv("README_KEY_ALIAS")
                ?: (findProperty("readmeKeyAlias") as? String)
            val keyPass = System.getenv("README_KEY_PASSWORD")
                ?: (findProperty("readmeKeyPassword") as? String)

            if (!storeFilePath.isNullOrBlank() && !storePass.isNullOrBlank() && !keyAl.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                val file = file(storeFilePath)
                if (file.exists()) {
                    storeFile = file
                    storePassword = storePass
                    keyAlias = keyAl
                    keyPassword = keyPass
                }
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
            optimization {
                enable = false
            }
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.pdf:pdf-document-service:1.0.0-beta01")
    implementation("androidx.pdf:pdf-viewer:1.0.0-beta01")
    implementation("androidx.pdf:pdf-ocr-play-services:1.0.0-beta01")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}