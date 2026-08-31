# Sidekick (Android)

Phone-first AI teammates for Android. Kotlin, Jetpack Compose, Hermes Agent architecture, MIT licensed.

This is the M0 project skeleton — a single-module Compose app that builds an APK and
launches a home screen with three teammate cards (Coder, Builder, Researcher). Tap a
card to enter a placeholder conversation screen.

## Build

```bash
export ANDROID_HOME=/c/Android-sdk          # or your SDK path
./gradlew --version
./gradlew check
./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Project layout

```
app/
  src/main/AndroidManifest.xml
  src/main/java/com/sidekick/app/
    MainActivity.kt
    SidekickApp.kt
    ui/
      HomeScreen.kt
      ConversationScreen.kt
      theme/
        Color.kt
        Theme.kt
        Type.kt
  src/test/java/com/sidekick/app/
    ExampleTest.kt
build.gradle.kts
settings.gradle.kts
gradle.properties
gradle/wrapper/
```

## Versions

- Kotlin 1.9.24
- Android Gradle Plugin 8.5.2
- Gradle 8.7
- Compose BOM 2024.06.00
- compileSdk 34, targetSdk 34, minSdk 24

## License

MIT — see [LICENSE](LICENSE).
