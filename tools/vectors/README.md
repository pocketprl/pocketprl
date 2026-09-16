# Conformance vectors

`pearl_vectors.json` in `app/src/test/resources` is generated from the real Pearl
wallet code so PocketPRL can prove it derives and signs exactly like oyster.

```bash
git clone --depth 1 https://github.com/pearl-research-labs/pearl /tmp/pearl
PEARL_DIR=/tmp/pearl ./tools/vectors/gen-vectors.sh
./gradlew :app:testDebugUnitTest
```

Requirements: Go 1.26+, gcc/g++, make (for `libxmss.a`).

The generator copies `pocketprl_vectors_test.go` into `wallet/wallet/` of the
monorepo, runs `TestPocketPRLVectors` with `-tags xmss`, and removes the file.
