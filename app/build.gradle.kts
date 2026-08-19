plugins {
    id("com.android.application")
}

android {
    namespace = "dev.vhos.headunit"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.vhos.headunit"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "0.1.0-dev.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    implementation(project(":core:discovery"))
    implementation(project(":core:digitaltwin"))
    implementation(project(":core:model"))
    implementation(project(":core:protocol"))
    implementation(project(":core:release"))
    implementation(project(":core:sync"))
    implementation(project(":data:store"))
    implementation(project(":transport:ble"))
    implementation("androidx.core:core:1.17.0")
}
