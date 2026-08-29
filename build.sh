#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_STUDIO_JAVA="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

if [[ -d "$ANDROID_STUDIO_JAVA" ]]; then
  export JAVA_HOME="$ANDROID_STUDIO_JAVA"
fi

cd "$PROJECT_DIR"
./gradlew --no-daemon :app:lintRelease :app:assembleRelease

APK_DIR="$PROJECT_DIR/app/build/outputs/apk/release"
SIGNED_APK="$APK_DIR/BiliFix-release.apk"
UNSIGNED_APK="$APK_DIR/BiliFix-release-unsigned.apk"

if [[ -f "$SIGNED_APK" ]]; then
  APK_PATH="$SIGNED_APK"
  SIGNING_STATE="signed"
elif [[ -f "$UNSIGNED_APK" ]]; then
  APK_PATH="$UNSIGNED_APK"
  SIGNING_STATE="unsigned"
else
  echo "Release APK was not produced in $APK_DIR" >&2
  exit 1
fi

echo "APK ($SIGNING_STATE): $APK_PATH"
shasum -a 256 "$APK_PATH"
