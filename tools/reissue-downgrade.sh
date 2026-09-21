#!/usr/bin/env bash
#
# Rebuild a historical PocketPRL tag with a version code from the downgrade band
# so Android will install it in place over a newer build. The tag's own tree is
# never modified: the build happens in a throwaway git worktree.
#
#   tools/reissue-downgrade.sh <tag> <versionCode> [outdir]
#   tools/reissue-downgrade.sh v1.1.0 100002 /tmp/reissue
#
# Requirements:
#   * the release signing env (POCKETPRL_KEYSTORE, POCKETPRL_KEYSTORE_PASSWORD,
#     POCKETPRL_KEY_ALIAS, POCKETPRL_KEY_PASSWORD)
#   * JDK 17 (export JAVA_HOME=/path/to/jdk-17)
#   * local.properties with sdk.dir (copied from the repo root)
#
# Writes <outdir>/PocketPRL-<version>.apk, and PocketPRL-<version>-mapping.txt.gz
# when R8 produced a mapping. Prints the SHA-256, version code and signer so the
# asset can be checked before upload. See docs/VERSIONING.md.
set -euo pipefail

TAG="${1:?usage: reissue-downgrade.sh <tag> <versionCode> [outdir]}"
CODE="${2:?usage: reissue-downgrade.sh <tag> <versionCode> [outdir]}"
OUTDIR="${3:-reissue-out}"

case "$CODE" in
  *[!0-9]*|'') echo "error: versionCode must be an integer, got '$CODE'" >&2; exit 2 ;;
esac

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

if [ -z "${POCKETPRL_KEYSTORE:-}" ]; then
  echo "error: POCKETPRL_KEYSTORE is not set; source the release env first." >&2
  exit 2
fi

VERSION="${TAG#v}"
WT="$(mktemp -d "/tmp/pocketprl-reissue-${TAG}.XXXXXX")"

cleanup() { git worktree remove --force "$WT" >/dev/null 2>&1 || rm -rf "$WT"; }
trap cleanup EXIT

echo "== $TAG (versionCode $CODE) =="
git worktree add --detach "$WT" "$TAG" >/dev/null
cp "$ROOT/local.properties" "$WT/local.properties" 2>/dev/null || true

# The code a regular build of this tag carries: the property if the tag has it,
# else the literal. Read before patching. A return build keeps this so the app can
# tell it is above the regular one.
NORMAL="$(grep -oE '^pocketprl\.versionCode=[0-9]+' "$WT/gradle.properties" 2>/dev/null | cut -d= -f2)"
if [ -z "$NORMAL" ]; then
  NORMAL="$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$WT/app/build.gradle.kts" | grep -oE '[0-9]+' | head -1)"
fi

# These tags predate the pocketprl.versionCode build property, so set the code directly.
if ! grep -qE '^[[:space:]]*versionCode[[:space:]]*=' "$WT/app/build.gradle.kts"; then
  echo "error: no versionCode line found in $TAG/app/build.gradle.kts" >&2
  exit 1
fi
sed -i -E "s/^([[:space:]]*)versionCode[[:space:]]*=[[:space:]]*[0-9]+/\1versionCode = ${CODE}/" "$WT/app/build.gradle.kts"

# Tags with the pocketprl.versionCode hook read the property; older tags ignore
# it and use the literal patched above. normalVersionCode is what makes the app
# recognise the result as a return build.
( cd "$WT" && ./gradlew :app:assembleRelease --no-configuration-cache -q -Ppocketprl.versionCode="$CODE" ${NORMAL:+-Ppocketprl.normalVersionCode="$NORMAL"} )

APK="$WT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "error: $TAG produced no APK" >&2; exit 1; }

mkdir -p "$OUTDIR"
cp "$APK" "$OUTDIR/PocketPRL-${VERSION}.apk"

MAP="$WT/app/build/outputs/mapping/release/mapping.txt"
if [ -f "$MAP" ]; then
  gzip -c "$MAP" > "$OUTDIR/PocketPRL-${VERSION}-mapping.txt.gz"
fi

# Verify what we are about to publish: package, version, signer.
AAPT="$(command -v aapt || true)"
API="$(ls -1 "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" 2>/dev/null | sort -V | tail -1)"
[ -z "$AAPT" ] && [ -n "$API" ] && AAPT="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/$API/aapt"
APKSIGNER="${AAPT%/*}/apksigner"

echo "-- sha256"
sha256sum "$OUTDIR/PocketPRL-${VERSION}.apk"
if [ -x "$AAPT" ]; then
  echo "-- badging"
  "$AAPT" dump badging "$OUTDIR/PocketPRL-${VERSION}.apk" 2>/dev/null | grep -E "^package:" || true
fi
if [ -x "$APKSIGNER" ]; then
  echo "-- signer"
  "$APKSIGNER" verify --print-certs "$OUTDIR/PocketPRL-${VERSION}.apk" 2>/dev/null | grep -iE "certificate SHA-256" || true
fi
echo "-- ok: $OUTDIR/PocketPRL-${VERSION}.apk"
