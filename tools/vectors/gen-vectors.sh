#!/usr/bin/env bash
# Regenerates app/src/test/resources/pearl_vectors.json from the real Pearl
# wallet code. Requires: Go >= 1.26, a C/C++ compiler, make, and a checkout of
# https://github.com/pearl-research-labs/pearl (PEARL_DIR, default /tmp/pearl).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PEARL_DIR="${PEARL_DIR:-/tmp/pearl}"
OUT="${1:-$ROOT/app/src/test/resources/pearl_vectors.json}"

if [ ! -d "$PEARL_DIR/wallet/wallet" ]; then
  echo "Pearl monorepo not found at $PEARL_DIR (set PEARL_DIR or clone it)" >&2
  echo "  git clone --depth 1 https://github.com/pearl-research-labs/pearl $PEARL_DIR" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
TARGET="$PEARL_DIR/wallet/wallet/pocketprl_vectors_test.go"
cp "$HERE/pocketprl_vectors_test.go" "$TARGET"
trap 'rm -f "$TARGET"' EXIT

(
  cd "$PEARL_DIR"
  make -C xmss >/dev/null
  POCKETPRL_VECTORS_OUT="$OUT" go test -tags xmss -count=1 -run '^TestPocketPRLVectors$' ./wallet/wallet/ -v 2>&1 | tail -5
)
echo "vectors written to $OUT"
