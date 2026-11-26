import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Published releases are signed with the maintainer's key, described by a properties file outside
// the repo (storeFile, storePassword, keyAlias, keyPassword). Everyone else gets their debug key.
val signingFile = System.getenv("RELEASE_SIGNING")?.let { file(it) }

android {
    namespace = "com.home.dashboard"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.home.dashboard"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "1.2.0"
    }
    signingConfigs {
        if (signingFile != null) create("published") {
            val p = Properties().apply { signingFile.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        // What goes on the TVs: optimised and non-debuggable (debug Compose builds are very slow).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (signingFile != null) "published" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
