package dev.pocketprl.data

import dev.pocketprl.core.chain.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WalletRegistryTest {
    private fun tempDir(): File = Files.createTempDirectory("pocketprl-registry").toFile().also { it.deleteOnExit() }

    @Test
    fun freshInstallHasNoWallets() {
        val r = WalletRegistry(tempDir())
        assertTrue(r.wallets.isEmpty())
        assertNull(r.activeId)
    }

    @Test
    fun legacySingleWalletIsRegisteredInPlace() {
        val dir = tempDir()
        File(dir, WalletRegistry.LEGACY_VAULT).writeText("""{"version":1,"walletName":"Old","network":"testnet2","createdAt":1234,"seedKind":"mnemonic"}""")
        val r = WalletRegistry(dir)
        assertEquals(1, r.wallets.size)
        val w = r.wallets.single()
        assertEquals(WalletRegistry.LEGACY_ID, w.id)
        assertEquals("Old", w.name)
        assertEquals(Network.TESTNET2.id, w.network)
        assertEquals(1234L, w.createdAt)
        assertEquals(WalletRegistry.LEGACY_VAULT, w.vaultFile)
        assertEquals(WalletRegistry.LEGACY_DB, w.dbName)
        assertEquals(WalletRegistry.LEGACY_ALIAS, w.keystoreAlias)
        assertEquals(WalletRegistry.LEGACY_ID, r.activeId)
        // Persisted: a second instance reads the index instead of re-migrating.
        assertTrue(File(dir, WalletRegistry.FILE_NAME).exists())
        assertEquals(r.wallets, WalletRegistry(dir).wallets)
    }

    @Test
    fun addRemoveSwitchRename() {
        val dir = tempDir()
        val r = WalletRegistry(dir)
        val a = r.newEntry("A", Network.MAINNET)
        val b = r.newEntry("B", Network.TESTNET2)
        assertNotEquals(a.id, b.id)
        assertTrue(a.vaultFile != b.vaultFile && a.dbName != b.dbName && a.keystoreAlias != b.keystoreAlias)
        assertTrue(a.keystoreAlias.startsWith(WalletRegistry.LEGACY_ALIAS + "."))

        r.add(a)
        assertEquals(a.id, r.activeId)
        r.add(b)
        assertEquals("adding makes the new wallet active", b.id, r.activeId)

        r.setActive(a.id)
        assertEquals(a.id, r.activeId)
        r.setActive("nope")
        assertEquals(a.id, r.activeId)

        r.rename(a.id, "  Alpha ")
        assertEquals("Alpha", r.get(a.id)!!.name)

        r.remove(a.id)
        assertEquals("removing the active wallet falls back to the newest remaining one", b.id, r.activeId)
        r.remove(b.id)
        assertTrue(r.wallets.isEmpty())
        assertNull(r.activeId)

        // Everything above survived a reload.
        val again = WalletRegistry(dir)
        assertTrue(again.wallets.isEmpty())
        assertNull(again.activeId)
    }
}
