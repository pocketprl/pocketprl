#!/usr/bin/env bash
#
# Reproducibility check for the release APK.
#
# Builds the release APK twice from clean, with the Gradle build cache disabled,
# and compares the two. Prints whether the whole APK matches; if not, it isolates
# the difference to the APK signing block and reports which blocks vary.
#
# This tests the current machine only. Cross-machine reproducibility is the real
# goal; run this on two machines (or in CI) and compare the printed hashes.
#
# Usage:
#   tools/check-reproducibility.sh [output-dir]
#
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-/tmp/pocketprl-repro}"
mkdir -p "$OUT"

build_once() {
  local dest="$1"
  # clean + no build cache, so nothing is reused between the two builds
  ./gradlew :app:clean :app:assembleRelease --no-build-cache --console=plain >/dev/null
  cp app/build/outputs/apk/release/app-release.apk "$dest"
}

echo "== build 1 =="
build_once "$OUT/repro1.apk"
echo "== build 2 =="
build_once "$OUT/repro2.apk"

echo
echo "== hashes =="
sha256sum "$OUT/repro1.apk" "$OUT/repro2.apk"

if cmp -s "$OUT/repro1.apk" "$OUT/repro2.apk"; then
  echo
  echo "PASS: two clean builds are byte-for-byte identical."
  exit 0
fi

echo
echo "DIFFER: the two APKs are not byte-identical. Isolating the difference..."
python3 - "$OUT/repro1.apk" "$OUT/repro2.apk" <<'PY'
import sys, struct, zipfile

a_path, b_path = sys.argv[1], sys.argv[2]
a = open(a_path, 'rb').read()
b = open(b_path, 'rb').read()

def entries(path):
    z = zipfile.ZipFile(path)
    return [(i.filename, i.CRC, i.file_size, i.compress_size, i.compress_type) for i in z.infolist()]

ea, eb = entries(a_path), entries(b_path)
if [n for n, *_ in ea] != [n for n, *_ in eb]:
    print("entry ORDER differs")
else:
    print("entry order: same")
diff_entries = [na for (na, *ra), (nb, *rb) in zip(ea, eb) if ra != rb]
print(f"entries differing (CRC/size): {len(diff_entries)}")
for n in diff_entries[:20]:
    print("   ", n)

def signing_blocks(d):
    m = d.rfind(b'APK Sig Block 42')
    if m < 0:
        return None
    size = struct.unpack('<Q', d[m-8:m])[0]
    start = m + 8 - size
    p, out = start + 8, []
    while p < m - 8:
        ln = struct.unpack('<Q', d[p:p+8])[0]
        pid = d[p+8:p+12]
        out.append((pid.decode('latin1'), d[p+8:p+8+ln]))
        p += 8 + ln
    return out

sa, sb = signing_blocks(a), signing_blocks(b)
if sa is None or sb is None:
    print("no APK signing block on one side; cannot localise")
else:
    print("signing blocks:")
    for (na, va), (nb, vb) in zip(sa, sb):
        print(f"   {na!r:12} len={len(va):6d} same={va == vb}")

# Content outside the signing block (ZIP entries + central directory) should match.
def content_prefix_end(d):
    m = d.rfind(b'APK Sig Block 42')
    size = struct.unpack('<Q', d[m-8:m])[0]
    return m + 8 - size

ca, cb = content_prefix_end(a), content_prefix_end(b)
print(f"content before signing block identical: {a[:ca] == b[:cb]}  ({ca} bytes)")
cd_a, cd_b = a.find(b'PK\x01\x02'), b.find(b'PK\x01\x02')
print(f"central directory + EOCD identical:     {a[cd_a:] == b[cd_b:]}")
PY
