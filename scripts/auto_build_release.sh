#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-temp}"
ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT_DIR/.android-user}"

echo "[auto-build] Watching for changes and building release APK..."
echo "[auto-build] Press Ctrl+C to stop."

cd "$ROOT_DIR"
GRADLE_USER_HOME="$GRADLE_USER_HOME" ANDROID_USER_HOME="$ANDROID_USER_HOME" ./gradlew assembleRelease --continuous \
  -Dkotlin.compiler.execution.strategy=in-process
