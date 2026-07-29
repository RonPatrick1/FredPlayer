# FredPlayer for Android

The Google Play application ID is `com.silveronstudios.fredplayer`.

Release bundles use Play App Signing. Keep the upload keystore outside this
repository and place these values in the user-level Gradle properties file
(`~/.gradle/gradle.properties`), never in source control:

```properties
FREDPLAYER_UPLOAD_STORE_FILE=/absolute/path/to/fredplayer-upload.jks
FREDPLAYER_UPLOAD_STORE_PASSWORD=replace-me
FREDPLAYER_UPLOAD_KEY_ALIAS=fredplayer-upload
FREDPLAYER_UPLOAD_KEY_PASSWORD=replace-me
```

Build the Play bundle with `./gradlew clean lintRelease bundleRelease`. Without
all four properties, Gradle intentionally produces an unsigned local bundle.
