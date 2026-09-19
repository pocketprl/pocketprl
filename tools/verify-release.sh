#!/usr/bin/env bash
#
# Verify a PocketPRL release APK.
#
#   tools/verify-release.sh /path/to/PocketPRL-1.1.1.apk [locally-built.apk]
#
# Checks the signing certificate against the published fingerprint, prints the
# APK's SHA-256, and (if given a second APK and apksigcopier is installed)
# compares the two ignoring the signature block.
set -euo pipefail

EXPECTED_CERT="5D:FF:D4:A7:05:13:5B:0E:85:A9:D3:C6:03:70:E8:27:BB:A4:AA:5B:5A:2E:C2:0F:A9:07:44:EC:67:2C:C8:55"

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  echo "usage: $0 <release.apk> [locally-built.apk]" >&2
  exit 2
fi

RELEASE="$1"
LOCAL="${2:-}"

if [ ! -f "$RELEASE" ]; then
  echo "error: no such file: $RELEASE" >&2
  exit 1
fi

# Locate apksigner from the Android SDK, or PATH.
APKSIGNER="$(command -v apksigner || true)"
if [ -z "$APKSIGNER" ]; then
  for d in "${ANDROID_HOME:-}/build-tools" "$HOME/Android/Sdk/build-tools"; do
    [ -d "$d" ] || continue
    APKSIGNER="$(ls -1 "$d" | sort -V | tail -1 | sed "s|^|$d/|")/apksigner"
    [ -x "$APKSIGNER" ] && break
  done
fi

echo "== SHA-256 =="
sha256sum "$RELEASE"

if [ -n "$LOCAL" ]; then
  echo
  echo "== SHA-256 (local build) =="
  sha256sum "$LOCAL"
fi

if [ -z "$APKSIGNER" ] || [ ! -x "$APKSIGNER" ]; then
  echo
  echo "warning: apksigner not found. Set ANDROID_HOME or install build-tools." >&2
  exit 0
fi

echo
echo "== Signer =="
# The stack-trace warnings are noise from the JDK, not verification failures.
CERTS="$("$APKSIGNER" verify --print-certs "$RELEASE" 2>/dev/null || true)"
echo "$CERTS" | grep -E "DN:|SHA-256 digest" || true

ACTUAL="$(echo "$CERTS" | sed -n 's/.*SHA-256 digest: //Ip' | tr 'a-f' 'A-F' | tr -d ' ' | sed 's/../&:/g;s/:$//')"
if [ "$ACTUAL" != "$EXPECTED_CERT" ]; then
  echo
  echo "FAIL: signing certificate does not match the published fingerprint." >&2
  exit 1
fi
echo
echo "OK: signing certificate matches."

if [ -n "$LOCAL" ]; then
  echo
  if command -v apksigcopier >/dev/null 2>&1; then
    echo "== Content comparison (apksigcopier) =="
    apksigcopier compare "$RELEASE" "$LOCAL" && echo "OK: contents match." || {
      echo "FAIL: contents differ (or apksigcopier could not compare)." >&2
      exit 1
    }
  else
    echo "note: apksigcopier not installed; skipping content comparison."
    echo "      pip install apksigcopier"
  fi
fi
