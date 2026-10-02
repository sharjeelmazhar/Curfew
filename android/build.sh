#!/usr/bin/env bash
# Builds the app with the toolchain in ../.toolchain and copies the APK to ../Curfew.apk
#   ./build.sh          unit tests + release APK
#   ./build.sh quick    release APK only
#   ./build.sh <tasks>  any gradle tasks
set -euo pipefail
cd "$(dirname "$0")"
TC="$(cd .. && pwd)/.toolchain"
export JAVA_HOME="$TC/jdk" ANDROID_HOME="$TC/android-sdk" GRADLE_USER_HOME="$TC/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"
echo "sdk.dir=$ANDROID_HOME" > local.properties
if [ ! -f curfew-release.jks ]; then
  keytool -genkeypair -keystore curfew-release.jks -storepass curfew-local -keypass curfew-local \
    -alias curfew -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Curfew" >/dev/null 2>&1
fi
if [ "${1:-}" = quick ]; then ./gradlew --console=plain -q assembleRelease
elif [ $# -gt 0 ]; then exec ./gradlew --console=plain "$@"
else ./gradlew --console=plain testDebugUnitTest assembleRelease; fi
cp app/build/outputs/apk/release/app-release.apk ../Curfew.apk
ls -la ../Curfew.apk
