import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun developmentSetting(name: String): String =
    localProperties.getProperty(name)
        ?: providers.environmentVariable(name).orNull
        ?: ""

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"") + "\""

android {
    namespace = "com.silveronstudios.fredplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.silveronstudios.fredplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DEV_SERVER_URL", "\"\"")
        buildConfigField("String", "DEV_SERVER_TOKEN", "\"\"")
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
        getByName("debug") {
            buildConfigField(
                "String",
                "DEV_SERVER_URL",
                buildConfigString(developmentSetting("FREDPLAYER_DEV_SERVER_URL"))
            )
            buildConfigField(
                "String",
                "DEV_SERVER_TOKEN",
                buildConfigString(developmentSetting("FREDPLAYER_DEV_SERVER_TOKEN"))
            )
        }
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
