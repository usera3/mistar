plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val signingPath = providers.environmentVariable("QINGLIAO_KEYSTORE").orNull

android {
    namespace = "cn.qingliao.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.qingliao.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 35
        versionName = "2.15.0"
        testInstrumentationRunner = "cn.qingliao.app.CryptoInstrumentation"
        buildConfigField("boolean", "FORCE_RELAY", (project.findProperty("forceRelay") == "true").toString())
        ndk { abiFilters += if (project.findProperty("phoneBuild") == "true") listOf("arm64-v8a", "armeabi-v7a") else listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    testBuildType = "release"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("release") {
            if (signingPath != null) {
                storeFile = file(signingPath)
                storePassword = System.getenv("QINGLIAO_SIGN_PASSWORD")
                keyAlias = "qingliao"
                keyPassword = System.getenv("QINGLIAO_SIGN_PASSWORD")
            }
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; if (signingPath != null) signingConfig = signingConfigs.getByName("release") }
    }
}
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("androidx.core:core-ktx:1.16.0")
}
