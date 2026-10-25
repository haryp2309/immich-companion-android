#!/usr/bin/env bash
# Builds the tsnet bridge into app/libs/tsbridge.aar with gomobile.
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$(go env GOPATH)/bin:$PATH"

command -v gomobile >/dev/null || go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest

mkdir -p ../app/libs
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 33 \
  -javapkg=app.immichcompanion \
  -ldflags="-s -w" \
  -trimpath \
  -o ../app/libs/tsbridge.aar \
  .
echo "Built app/libs/tsbridge.aar"
