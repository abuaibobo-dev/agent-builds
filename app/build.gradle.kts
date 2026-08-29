plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.aiphotoapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aiphotoapp"
        minSdk = 24
        targetSdk = 34
        versionCode = run {
            val fromCi = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
            fromCi ?: 2
        }
        versionName = run {
            val buildNo = System.getenv("GITHUB_RUN_NUMBER")
            if (buildNo != null) "1.0.$buildNo" else "1.0"
        }

        buildConfigField("String", "AGNES_API_KEY", "\"${project.findProperty("AGNES_API_KEY") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 网络请求库 OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
