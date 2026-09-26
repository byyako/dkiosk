plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tyllad.dkiosk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tyllad.dkiosk"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.material)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
