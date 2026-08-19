#!/usr/bin/env bash
#
# Build, sign, verify and fingerprint both release APK variants (Kugou + Luna identity), then
# stage them for a GitHub Release.
#
# Run this on the machine that holds your signing key, from the repo root:
#     ./scripts/release.sh
#
# Prerequisites on that machine:
#   - keystore.properties present at the repo root (copy from keystore.properties.template)
#   - the referenced .jks keystore present
#   - Android SDK build-tools on PATH (for apksigner), or ANDROID_HOME/ANDROID_SDK_ROOT set
#
# Output (in ./dist/), one pair per flavor:
#   - origin-ghost-player-kugou-<version>.apk / .apk.sha256
#   - origin-ghost-player-luna-<version>.apk  / .apk.sha256
#   - a printed signing-certificate SHA-256 fingerprint per APK — should match origin-isle's and
#     each other, since both variants and origin-isle are signed with the same key.
#
set -euo pipefail

# --- locate repo root (this script lives in <root>/scripts) -------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# --- refuse to produce an unsigned "release" silently -------------------------------------
if [[ ! -f keystore.properties ]]; then
  echo "ERROR: keystore.properties not found at repo root." >&2
  echo "       Copy keystore.properties.template -> keystore.properties and fill it in on the" >&2
  echo "       machine that has your signing key. assembleRelease would otherwise build UNSIGNED." >&2
  exit 1
fi

# --- read versionName from app/build.gradle.kts for nice artifact names -------------------
VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n1)"
VERSION="${VERSION:-unversioned}"

# --- resolve apksigner --------------------------------------------------------------------
APKSIGNER=""
if command -v apksigner >/dev/null 2>&1; then
  APKSIGNER="apksigner"
else
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$SDK" ]]; then
    # pick the highest-versioned build-tools that ships apksigner
    APKSIGNER="$(ls -1 "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n1 || true)"
  fi
fi

mkdir -p dist

for FLAVOR in kugou luna; do
  FLAVOR_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"

  echo "==> Building signed release: $FLAVOR (versionName=$VERSION) ..."
  ./gradlew ":app:assemble${FLAVOR_CAP}Release"

  APK_IN="app/build/outputs/apk/${FLAVOR}/release/app-${FLAVOR}-release.apk"
  if [[ ! -f "$APK_IN" ]]; then
    echo "ERROR: expected APK not found at $APK_IN" >&2
    exit 1
  fi

  if [[ -n "$APKSIGNER" ]]; then
    echo "==> Verifying signature ($FLAVOR) ..."
    "$APKSIGNER" verify --print-certs "$APK_IN"
  else
    echo "WARNING: apksigner not found (set ANDROID_HOME or add build-tools to PATH)." >&2
    echo "         Skipping signature verification — the APK is still signed if the build" >&2
    echo "         succeeded with keystore.properties present." >&2
  fi

  APK_OUT="dist/origin-ghost-player-${FLAVOR}-${VERSION}.apk"
  cp -f "$APK_IN" "$APK_OUT"

  if command -v shasum >/dev/null 2>&1; then
    ( cd dist && shasum -a 256 "origin-ghost-player-${FLAVOR}-${VERSION}.apk" | tee "origin-ghost-player-${FLAVOR}-${VERSION}.apk.sha256" )
  elif command -v sha256sum >/dev/null 2>&1; then
    ( cd dist && sha256sum "origin-ghost-player-${FLAVOR}-${VERSION}.apk" | tee "origin-ghost-player-${FLAVOR}-${VERSION}.apk.sha256" )
  else
    echo "WARNING: no shasum/sha256sum available; skipping checksum file." >&2
  fi
  echo
done

echo "==> Done."
echo "    dist/origin-ghost-player-kugou-${VERSION}.apk (+ .sha256)"
echo "    dist/origin-ghost-player-luna-${VERSION}.apk  (+ .sha256)"
echo
echo "    Upload all four files to the same GitHub Release. The certificate fingerprints printed"
echo "    above should match origin-isle's and each other, since everything is signed with the"
echo "    same key."
