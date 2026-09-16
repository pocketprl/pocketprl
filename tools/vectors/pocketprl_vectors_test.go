//go:build xmss

// PocketPRL conformance vector generator.
//
// This file is copied into the Pearl monorepo (wallet/wallet/) by
// tools/vectors/gen-vectors.sh and executed there with `go test -tags xmss`.
// It drives the *real* oyster wallet code (waddrmgr, hdkeychain, txscript,
// xmss cgo bindings) to produce ground-truth values that the PocketPRL Kotlin
// implementation must reproduce byte-for-byte.
//
// Output: JSON written to $POCKETPRL_VECTORS_OUT.
package wallet

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"testing"
	"time"

	"github.com/pearl-research-labs/pearl/node/btcec"
	"github.com/pearl-research-labs/pearl/node/btcec/schnorr"
	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/btcutil/hdkeychain"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/chaincfg/chainhash"
	"github.com/pearl-research-labs/pearl/node/txscript"
	"github.com/pearl-research-labs/pearl/node/wire"
	"github.com/pearl-research-labs/pearl/wallet/waddrmgr"
	"github.com/pearl-research-labs/pearl/wallet/walletdb"
	"github.com/pearl-research-labs/pearl/xmss"
	"github.com/stretchr/testify/require"
	bip39 "github.com/tyler-smith/go-bip39"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/sha3"
)

type vecShake struct {
	In     string `json:"in"`
	OutLen int    `json:"outLen"`
	Out    string `json:"out"`
}

type vecXMSS struct {
	PrivSeed string `json:"privSeed"`
	PubSeed  string `json:"pubSeed"`
	PubKey   string `json:"pubKey"`
}

type vecHKDF struct {
	IKM string `json:"ikm"`
	Out string `json:"out"`
}

type vecBip32Level struct {
	Index     uint32 `json:"index"` // raw child index (>= 0x80000000 for hardened)
	PrivKey   string `json:"privKey"`
	ChainCode string `json:"chainCode"`
	KeyLen    int    `json:"rawKeyLen"` // length of the un-padded key as stored by hdkeychain
}

type vecBip32 struct {
	Note    string          `json:"note"`
	SeedHex string          `json:"seedHex"`
	Levels  []vecBip32Level `json:"levels"`
}

type vecAddress struct {
	Branch        uint32 `json:"branch"`
	Index         uint32 `json:"index"`
	Path          string `json:"path"`
	PrivKey       string `json:"privKey"`
	InternalPub   string `json:"internalPubKey"`
	PQPrivKey     string `json:"pqPrivKey"`
	XMSSPrivSeed  string `json:"xmssPrivSeed"`
	XMSSPubSeed   string `json:"xmssPubSeed"`
	XMSSPubKey    string `json:"xmssPubKey"`
	TapscriptRoot string `json:"tapscriptRoot"`
	OutputKey     string `json:"outputKey"`
	PkScript      string `json:"pkScript"`
	Address       string `json:"address"`
	// The same index without the XMSS tapleaf: what oyster's getnewaddress /
	// getrawchangeaddress hand out by default (usePQ=false) and what the
	// desktop wallet therefore uses everywhere.
	PlainOutputKey string `json:"plainOutputKey"`
	PlainPkScript  string `json:"plainPkScript"`
	PlainAddress   string `json:"plainAddress"`
}

type vecSpendInput struct {
	Branch         uint32 `json:"branch"`
	Index          uint32 `json:"index"`
	PrevTxid       string `json:"prevTxid"`
	PrevVout       uint32 `json:"prevVout"`
	Value          int64  `json:"value"`
	PkScript       string `json:"pkScript"`
	Sighash        string `json:"sighash"`
	TweakedPrivKey string `json:"tweakedPrivKey"`
	OutputKey      string `json:"outputKey"`
	Signature      string `json:"signature"`
}

type vecSpendOutput struct {
	Value    int64  `json:"value"`
	Address  string `json:"address"`
	PkScript string `json:"pkScript"`
}

type vecSpend struct {
	Version       int32            `json:"version"`
	LockTime      uint32           `json:"lockTime"`
	Inputs        []vecSpendInput  `json:"inputs"`
	Outputs       []vecSpendOutput `json:"outputs"`
	UnsignedTxHex string           `json:"unsignedTxHex"`
	SignedTxHex   string           `json:"signedTxHex"`
	Txid          string           `json:"txid"`
	Vsize         int64            `json:"vsize"`
}

type vecWallet struct {
	Name       string       `json:"name"`
	Network    string       `json:"network"`
	Mnemonic   string       `json:"mnemonic,omitempty"`
	SeedHex    string       `json:"seedHex"`
	CoinType   uint32       `json:"coinType"`
	HRP        string       `json:"hrp"`
	Addresses  []vecAddress `json:"addresses"`
	Spend      *vecSpend    `json:"spend,omitempty"`
	SpendPlain *vecSpend    `json:"spendPlain,omitempty"`
}

type vectorFile struct {
	GeneratedAt string      `json:"generatedAt"`
	Shake256    []vecShake  `json:"shake256"`
	XMSS        []vecXMSS   `json:"xmss"`
	HKDF        []vecHKDF   `json:"hkdf"`
	BIP32       []vecBip32  `json:"bip32"`
	Wallets     []vecWallet `json:"wallets"`
}

func hx(b []byte) string { return hex.EncodeToString(b) }

func shakeVectors() []vecShake {
	var out []vecShake
	inputs := [][]byte{{}, []byte("abc"), bytes.Repeat([]byte{0xa3}, 200)}
	for _, n := range []int{135, 136, 137, 271, 272, 273} {
		b := make([]byte, n)
		for i := range b {
			b[i] = byte(i * 7)
		}
		inputs = append(inputs, b)
	}
	for _, in := range inputs {
		for _, l := range []int{32, 64, 96} {
			o := make([]byte, l)
			sha3.ShakeSum256(o, in)
			out = append(out, vecShake{In: hx(in), OutLen: l, Out: hx(o)})
		}
	}
	return out
}

func xmssVectors(t *testing.T) []vecXMSS {
	var out []vecXMSS
	for i := 0; i < 2; i++ {
		ps := sha512.Sum512([]byte(fmt.Sprintf("pocketprl-xmss-priv-%d", i)))
		pb := sha256.Sum256([]byte(fmt.Sprintf("pocketprl-xmss-pub-%d", i)))
		pk, sk, err := xmss.Keygen(ps, pb)
		require.NoError(t, err)
		clear(sk[:])
		out = append(out, vecXMSS{PrivSeed: hx(ps[:]), PubSeed: hx(pb[:]), PubKey: hx(pk[:])})
	}
	return out
}

func hkdfVectors(t *testing.T) []vecHKDF {
	var out []vecHKDF
	for i := 0; i < 2; i++ {
		ikm := sha256.Sum256([]byte(fmt.Sprintf("pocketprl-hkdf-%d", i)))
		r := hkdf.New(sha256.New, ikm[:], nil, []byte("XMSS-SEED-EXPANSION"))
		o := make([]byte, xmss.PrivateSeedLen+xmss.PublicSeedLen)
		_, err := io.ReadFull(r, o)
		require.NoError(t, err)
		out = append(out, vecHKDF{IKM: hx(ikm[:]), Out: hx(o)})
	}
	return out
}

// hardened returns the hardened child index.
func hardened(i uint32) uint32 { return i + hdkeychain.HardenedKeyStart }

// bip32Vector walks m/86'/coin'/0'/0/0 with DeriveNonStandard and records
// every level so the Kotlin implementation can be checked level by level.
func bip32Vector(t *testing.T, note string, seed []byte, params *chaincfg.Params) vecBip32 {
	master, err := hdkeychain.NewMaster(seed, params)
	require.NoError(t, err)
	path := []uint32{hardened(86), hardened(params.HDCoinType), hardened(0), 0, 0}
	v := vecBip32{Note: note, SeedHex: hx(seed)}
	record := func(idx uint32, k *hdkeychain.ExtendedKey) {
		priv, err := k.ECPrivKey()
		require.NoError(t, err)
		ser := priv.Serialize()
		rawLen := 32
		for rawLen > 0 && ser[32-rawLen] == 0 {
			rawLen--
		}
		v.Levels = append(v.Levels, vecBip32Level{Index: idx, PrivKey: hx(ser), ChainCode: hx(k.ChainCode()), KeyLen: rawLen})
	}
	record(0, master)
	k := master
	for _, idx := range path {
		k, err = k.DeriveNonStandard(idx) // nolint:staticcheck
		require.NoError(t, err)
		record(idx, k)
	}
	return v
}

// findSeedWithShortKey searches for a random 16-byte seed such that the
// private key at the given hardened level (0=purpose', 1=coin', 2=account')
// has a leading zero byte. hdkeychain's DeriveNonStandard strips leading zeros
// from private keys, which changes the HMAC input of the *next* hardened
// derivation compared to BIP-32. PocketPRL must reproduce this quirk.
func findSeedWithShortKey(t *testing.T, params *chaincfg.Params, level int) []byte {
	levels := []uint32{hardened(86), hardened(params.HDCoinType), hardened(0)}
	for tries := 0; tries < 200000; tries++ {
		seed := make([]byte, 16)
		_, err := rand.Read(seed)
		require.NoError(t, err)
		k, err := hdkeychain.NewMaster(seed, params)
		if err != nil {
			continue
		}
		ok := true
		for l := 0; l <= level; l++ {
			k, err = k.DeriveNonStandard(levels[l]) // nolint:staticcheck
			if err != nil {
				ok = false
				break
			}
		}
		if !ok {
			continue
		}
		priv, err := k.ECPrivKey()
		require.NoError(t, err)
		if priv.Serialize()[0] == 0 {
			return seed
		}
	}
	t.Fatalf("no short key found for level %d", level)
	return nil
}

func serializeTx(t *testing.T, tx *wire.MsgTx) string {
	var buf bytes.Buffer
	require.NoError(t, tx.Serialize(&buf))
	return hx(buf.Bytes())
}

func serializeTxNoWitness(t *testing.T, tx *wire.MsgTx) string {
	var buf bytes.Buffer
	require.NoError(t, tx.SerializeNoWitness(&buf))
	return hx(buf.Bytes())
}

type spendKey struct {
	branch, index uint32
	priv          *btcec.PrivateKey
	root          []byte
	pkScript      []byte
	address       string
}

// spendVector builds, signs (key path, SIGHASH_DEFAULT) and script-engine
// verifies a 2-in/2-out spend across the given keys: in[0]+in[1] -> out[0]+out[1].
func spendVector(t *testing.T, name string, keys []spendKey, inIdx, outIdx []int) *vecSpend {
	inVals := []int64{5_0000_0000, 3_0000_0000}
	outVals := []int64{6_0000_0000, 1_9000_0000}

	tx := wire.NewMsgTx(2)
	prevOuts := map[wire.OutPoint]*wire.TxOut{}
	for i, ai := range inIdx {
		h := sha256.Sum256([]byte(fmt.Sprintf("%s-prev-%d", name, i)))
		var hash chainhash.Hash
		copy(hash[:], h[:])
		op := wire.OutPoint{Hash: hash, Index: uint32(i + 1)}
		tx.AddTxIn(wire.NewTxIn(&op, nil, nil))
		prevOuts[op] = &wire.TxOut{Value: inVals[i], PkScript: keys[ai].pkScript}
	}
	sp := &vecSpend{Version: 2, LockTime: 0}
	for i, ai := range outIdx {
		tx.AddTxOut(wire.NewTxOut(outVals[i], keys[ai].pkScript))
		sp.Outputs = append(sp.Outputs, vecSpendOutput{Value: outVals[i], Address: keys[ai].address, PkScript: hx(keys[ai].pkScript)})
	}
	fetcher := txscript.NewMultiPrevOutFetcher(prevOuts)
	sigHashes := txscript.NewTxSigHashes(tx, fetcher)
	sp.UnsignedTxHex = serializeTxNoWitness(t, tx)

	for i, ai := range inIdx {
		k := keys[ai]
		sh, err := txscript.CalcTaprootSignatureHash(sigHashes, txscript.SigHashDefault, tx, i, fetcher)
		require.NoError(t, err)
		tweaked := txscript.TweakTaprootPrivKey(*k.priv, k.root)
		sig, err := txscript.RawTxInTaprootSignature(
			tx, sigHashes, i, inVals[i], k.pkScript, k.root, txscript.SigHashDefault, k.priv,
		)
		require.NoError(t, err)
		tx.TxIn[i].Witness = wire.TxWitness{sig}
		outKey := txscript.ComputeTaprootOutputKey(k.priv.PubKey(), k.root)
		sp.Inputs = append(sp.Inputs, vecSpendInput{
			Branch:         k.branch,
			Index:          k.index,
			PrevTxid:       tx.TxIn[i].PreviousOutPoint.Hash.String(),
			PrevVout:       tx.TxIn[i].PreviousOutPoint.Index,
			Value:          inVals[i],
			PkScript:       hx(k.pkScript),
			Sighash:        hx(sh),
			TweakedPrivKey: hx(tweaked.Serialize()),
			OutputKey:      hx(schnorr.SerializePubKey(outKey)),
			Signature:      hx(sig),
		})
	}
	// Verify every input with the real script engine.
	for i, ai := range inIdx {
		vm, err := txscript.NewEngine(keys[ai].pkScript, tx, i, txscript.StandardVerifyFlags, nil, sigHashes, inVals[i], fetcher)
		require.NoError(t, err)
		require.NoError(t, vm.Execute(), "%s input %d must verify", name, i)
	}
	sp.SignedTxHex = serializeTx(t, tx)
	sp.Txid = tx.TxHash().String()
	sp.Vsize = int64((tx.SerializeSizeStripped()*3 + tx.SerializeSize() + 3) / 4)
	return sp
}

// openWallet creates a fresh oyster wallet from seed and hands out nExt
// receive + nInt change addresses with the given PQ flag, exactly through
// the code path the RPC handlers use.
func openWallet(t *testing.T, params *chaincfg.Params, seed []byte, nExt, nInt int, pq bool) (*Wallet, *Loader, []btcutil.Address) {
	dir := t.TempDir()
	loader := NewLoader(params, dir, true, defaultDBTimeout, 250,
		WithWalletSyncRetryInterval(10*time.Millisecond))
	w, err := loader.CreateNewWallet([]byte("public"), []byte("private"), seed, time.Now())
	require.NoError(t, err)
	require.NoError(t, w.Unlock([]byte("private"), time.After(time.Minute)))
	w.chainClient = &mockChainClient{}
	var addrs []btcutil.Address
	for i := 0; i < nExt; i++ {
		a, err := w.NewAddress(0, waddrmgr.KeyScopeBIP0086, pq)
		require.NoError(t, err)
		addrs = append(addrs, a)
	}
	for i := 0; i < nInt; i++ {
		a, err := w.NewChangeAddress(0, waddrmgr.KeyScopeBIP0086, pq)
		require.NoError(t, err)
		addrs = append(addrs, a)
	}
	return w, loader, addrs
}

func walletVectors(t *testing.T, name string, params *chaincfg.Params, mnemonic string, seed []byte, nExt, nInt int, withSpend bool) vecWallet {
	waddrmgr.InitKeyScopes(params.HDCoinType)

	// Two wallets from one seed: one with the XMSS tapleaf commitment, one
	// plain (the desktop default). Same derivation paths, different output keys.
	w, loader, addrs := openWallet(t, params, seed, nExt, nInt, true)
	defer func() { _ = loader.UnloadWallet() }()
	wp, loaderP, plainAddrs := openWallet(t, params, seed, nExt, nInt, false)
	defer func() { _ = loaderP.UnloadWallet() }()
	require.Len(t, plainAddrs, len(addrs))

	pqMgr, err := w.Manager.FetchScopedKeyManager(waddrmgr.KeyScopePQ)
	require.NoError(t, err)

	vw := vecWallet{
		Name: name, Network: params.Name, Mnemonic: mnemonic, SeedHex: hx(seed),
		CoinType: params.HDCoinType, HRP: params.Bech32HRPSegwit,
	}

	var pqKeys, plainKeys []spendKey

	for i, addr := range addrs {
		ma, err := w.AddressInfo(addr)
		require.NoError(t, err)
		pk, ok := ma.(waddrmgr.ManagedPubKeyAddress)
		require.True(t, ok)
		priv, err := pk.PrivKey()
		require.NoError(t, err)
		_, path, _ := pk.DerivationInfo()

		var privSeed [xmss.PrivateSeedLen]byte
		var pubSeed [xmss.PublicSeedLen]byte
		var pqPriv []byte
		err = walletdb.Update(w.Database(), func(tx walletdb.ReadWriteTx) error {
			ns := tx.ReadWriteBucket(waddrmgrNamespaceKey)
			var err error
			privSeed, pubSeed, err = waddrmgr.DeriveXMSSSeeds(pqMgr, ns, path)
			if err != nil {
				return err
			}
			pqAddr, err := pqMgr.DeriveFromKeyPath(ns, path, false)
			if err != nil {
				return err
			}
			pqPk, err := pqAddr.(waddrmgr.ManagedPubKeyAddress).PrivKey()
			if err != nil {
				return err
			}
			pqPriv = pqPk.Serialize()
			return nil
		})
		require.NoError(t, err)

		xpk, xsk, err := xmss.Keygen(privSeed, pubSeed)
		require.NoError(t, err)
		clear(xsk[:])

		root := pk.TapscriptRoot()
		require.Len(t, root, 32)
		outKey := txscript.ComputeTaprootOutputKey(priv.PubKey(), root)
		pkScript, err := txscript.PayToAddrScript(addr)
		require.NoError(t, err)
		expScript, err := txscript.PayToTaprootScript(outKey)
		require.NoError(t, err)
		require.Equal(t, expScript, pkScript)

		// Plain variant: same path and private key, no tapscript root.
		plainAddr := plainAddrs[i]
		maP, err := wp.AddressInfo(plainAddr)
		require.NoError(t, err)
		pkP, ok := maP.(waddrmgr.ManagedPubKeyAddress)
		require.True(t, ok)
		privP, err := pkP.PrivKey()
		require.NoError(t, err)
		_, pathP, _ := pkP.DerivationInfo()
		require.Equal(t, path, pathP, "plain and PQ wallets must walk the same path")
		require.Equal(t, priv.Serialize(), privP.Serialize())
		require.Empty(t, pkP.TapscriptRoot(), "plain address must carry no tapscript root")
		outKeyPlain := txscript.ComputeTaprootKeyNoScript(priv.PubKey())
		plainScript, err := txscript.PayToAddrScript(plainAddr)
		require.NoError(t, err)
		expPlain, err := txscript.PayToTaprootScript(outKeyPlain)
		require.NoError(t, err)
		require.Equal(t, expPlain, plainScript)
		require.NotEqual(t, pkScript, plainScript)

		branch := path.Branch
		vw.Addresses = append(vw.Addresses, vecAddress{
			Branch:         branch,
			Index:          path.Index,
			Path:           fmt.Sprintf("m/86'/%d'/%d'/%d/%d", params.HDCoinType, path.Account, branch, path.Index),
			PrivKey:        hx(priv.Serialize()),
			InternalPub:    hx(priv.PubKey().SerializeCompressed()),
			PQPrivKey:      hx(pqPriv),
			XMSSPrivSeed:   hx(privSeed[:]),
			XMSSPubSeed:    hx(pubSeed[:]),
			XMSSPubKey:     hx(xpk[:]),
			TapscriptRoot:  hx(root),
			OutputKey:      hx(schnorr.SerializePubKey(outKey)),
			PkScript:       hx(pkScript),
			Address:        addr.EncodeAddress(),
			PlainOutputKey: hx(schnorr.SerializePubKey(outKeyPlain)),
			PlainPkScript:  hx(plainScript),
			PlainAddress:   plainAddr.EncodeAddress(),
		})
		pqKeys = append(pqKeys, spendKey{branch: branch, index: path.Index, priv: priv, root: root, pkScript: pkScript, address: addr.EncodeAddress()})
		plainKeys = append(plainKeys, spendKey{branch: branch, index: path.Index, priv: priv, root: nil, pkScript: plainScript, address: plainAddr.EncodeAddress()})
	}

	if withSpend && nExt >= 2 && nInt >= 2 {
		// Spend ext[0] + int[0] -> ext[1] + change int[1].
		inIdx := []int{0, nExt}
		outIdx := []int{1, nExt + 1}
		vw.Spend = spendVector(t, name, pqKeys, inIdx, outIdx)
		vw.SpendPlain = spendVector(t, name+"-plain", plainKeys, inIdx, outIdx)
	}
	return vw
}

func TestPocketPRLVectors(t *testing.T) {
	outPath := os.Getenv("POCKETPRL_VECTORS_OUT")
	if outPath == "" {
		t.Skip("POCKETPRL_VECTORS_OUT not set")
	}
	t.Cleanup(useFastScrypt())

	vf := vectorFile{GeneratedAt: time.Now().UTC().Format(time.RFC3339)}
	vf.Shake256 = shakeVectors()
	vf.XMSS = xmssVectors(t)
	vf.HKDF = hkdfVectors(t)

	main := &chaincfg.MainNetParams
	test := &chaincfg.TestNet2Params

	// Well-known mnemonics.
	m12 := "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
	m24 := "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote"
	require.True(t, bip39.IsMnemonicValid(m12))
	require.True(t, bip39.IsMnemonicValid(m24))
	seed12 := bip39.NewSeed(m12, "")
	seed24 := bip39.NewSeed(m24, "")
	seedHex, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f")

	// Random mnemonic generated the same way oyster does it (128-bit entropy).
	ent, err := hdkeychain.GenerateSeed(hdkeychain.RecommendedSeedLen)
	require.NoError(t, err)
	mRand, err := bip39.NewMnemonic(ent)
	require.NoError(t, err)
	seedRand := bip39.NewSeed(mRand, "")

	vf.BIP32 = append(vf.BIP32, bip32Vector(t, "abandon x11 about, mainnet", seed12, main))
	shortSeeds := map[string][]byte{}
	for lvl, note := range []string{"purpose' key has leading zero byte", "coin' key has leading zero byte", "account' key has leading zero byte"} {
		s := findSeedWithShortKey(t, main, lvl)
		shortSeeds[note] = s
		vf.BIP32 = append(vf.BIP32, bip32Vector(t, note, s, main))
	}

	vf.Wallets = append(vf.Wallets,
		walletVectors(t, "mainnet-abandon12", main, m12, seed12, 3, 3, true),
		walletVectors(t, "mainnet-zoo24", main, m24, seed24, 2, 2, false),
		walletVectors(t, "mainnet-random12", main, mRand, seedRand, 2, 1, true),
		walletVectors(t, "testnet2-hexseed", test, "", seedHex, 3, 2, true),
		walletVectors(t, "testnet2-abandon12", test, m12, seed12, 2, 1, false),
	)
	i := 0
	for note, s := range shortSeeds {
		vf.Wallets = append(vf.Wallets, walletVectors(t, fmt.Sprintf("mainnet-shortkey-%d (%s)", i, note), main, "", s, 2, 1, false))
		i++
	}

	data, err := json.MarshalIndent(vf, "", "  ")
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(outPath, data, 0o644))
	t.Logf("wrote %d bytes to %s", len(data), outPath)
}
