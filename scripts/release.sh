#!/usr/bin/env bash
#
# Build, sign, verify and fingerprint a release APK, then stage it for a GitHub Release.
#
# Run this on the machine that holds your signing key, from the repo root:
#     ./scripts/release.sh
#
# Prerequisites on that machine:
#   - keystore.properties present at the repo root (copy from keystore.properties.template)
#   - the referenced .jks keystore present
#   - Android SDK build-tools on PATH (for apksigner), or ANDROID_HOME/ANDROID_SDK_ROOT set
#
# Output (in ./dist/):
#   - origin-ghost-player-<version>.apk        the signed APK to upload to the GitHub Release
#   - origin-ghost-player-<version>.apk.sha256 its SHA-256 checksum (upload this too)
#   - a printed signing-certificate SHA-256 fingerprint — should match origin-isle's, since this
#     project is signed with the same key.
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

# --- build --------------------------------------------------------------------------------
echo "==> Building signed release (versionName=$VERSION) ..."
./gradlew :app:assembleRelease

APK_IN="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK_IN" ]]; then
  echo "ERROR: expected APK not found at $APK_IN" >&2
  exit 1
fi

# --- verify signature ---------------------------------------------------------------------
if [[ -n "$APKSIGNER" ]]; then
  echo "==> Verifying signature ..."
  "$APKSIGNER" verify --print-certs "$APK_IN"
else
  echo "WARNING: apksigner not found (set ANDROID_HOME or add build-tools to PATH)." >&2
  echo "         Skipping signature verification — the APK is still signed if the build" >&2
  echo "         succeeded with keystore.properties present." >&2
fi

# --- stage artifacts + checksum -----------------------------------------------------------
mkdir -p dist
APK_OUT="dist/origin-ghost-player-${VERSION}.apk"
cp -f "$APK_IN" "$APK_OUT"

if command -v shasum >/dev/null 2>&1; then
  ( cd dist && shasum -a 256 "origin-ghost-player-${VERSION}.apk" | tee "origin-ghost-player-${VERSION}.apk.sha256" )
elif command -v sha256sum >/dev/null 2>&1; then
  ( cd dist && sha256sum "origin-ghost-player-${VERSION}.apk" | tee "origin-ghost-player-${VERSION}.apk.sha256" )
else
  echo "WARNING: no shasum/sha256sum available; skipping checksum file." >&2
fi

echo
echo "==> Done."
echo "    Signed APK : $APK_OUT"
echo "    Checksum   : ${APK_OUT}.sha256"
echo
echo "    Upload BOTH files to the GitHub Release. The certificate fingerprint printed above"
echo "    should match origin-isle's, since this project is signed with the same key."
