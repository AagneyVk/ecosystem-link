# Testing

Hub unit tests use pytest and pytest-asyncio. From `arch-hub`, run `python -m pytest -q`. Android unit tests use JUnit; from `android-agent`, run `./gradlew testDebugUnitTest assembleDebug` after restoring/generating a standard Gradle wrapper and installing JDK 17 plus Android SDK 35.

On the 2026-07-29 audit host, Python was an inaccessible Windows app alias and WSL was not installed. A cached Android Studio JDK and Gradle 8.10.2 were found, but the build could not resolve `kotlin-reflect:1.9.24`: offline mode had no cached artifact and online mode failed Maven Central certificate validation (`PKIX path building failed`). The Android checkout also lacks `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar`; consequently no new passing test result is claimed.
