plugins {
    id("com.android.application")
}

android {
    namespace = "com.silveronstudios.fredplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.silveronstudios.fredplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    val uploadStoreFile = providers.gradleProperty("FREDPLAYER_UPLOAD_STORE_FILE")
    val uploadStorePassword = providers.gradleProperty("FREDPLAYER_UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = providers.gradleProperty("FREDPLAYER_UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = providers.gradleProperty("FREDPLAYER_UPLOAD_KEY_PASSWORD")

    signingConfigs {
        if (uploadStoreFile.isPresent
                && uploadStorePassword.isPresent
                && uploadKeyAlias.isPresent
                && uploadKeyPassword.isPresent) {
            create("release") {
                storeFile = file(uploadStoreFile.get())
                storePassword = uploadStorePassword.get()
                keyAlias = uploadKeyAlias.get()
                keyPassword = uploadKeyPassword.get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media:media:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
