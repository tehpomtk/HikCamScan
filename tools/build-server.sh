#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="$HOME/develop/java"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain :app:assembleDebug :app:lintDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/HikCamScan-debug.apk
mkdir -p build/core-tests
javac -encoding UTF-8 -d build/core-tests app/src/main/java/ru/hikcamscan/{Camera,Xml,Discovery,CameraHttp,CameraClient}.java tests/ru/hikcamscan/CoreTest.java
java -cp build/core-tests ru.hikcamscan.CoreTest
