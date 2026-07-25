plugins {
    id("com.android.application")
}

android {
    namespace = "com.fredplayer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fredplayer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media:media:1.1.0")
}
