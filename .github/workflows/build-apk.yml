name: Build T-MINUS Android APK

on:
  push:
    branches: [main, master]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build-debug-apk:
    runs-on: ubuntu-latest
    steps:
      - name: Check out repository
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
       uses: android-actions/setup-android@v4
       with:
         packages: 'platform-tools'

      - name: Install Android SDK 36
        run: sdkmanager "platforms;android-36" "build-tools;36.0.0"

      - name: Set up Gradle 8.13
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.13'

      - name: Build debug APK
        run: gradle --no-daemon -p android :app:assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: T-MINUS-debug-apk
          path: android/app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
