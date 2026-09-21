package dev.pocketprl.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backward/forward compatibility of the on-disk data across app versions.
 *
 * Every release so far (1.0.0 … 2.5.3) shipped the same persisted formats, which
 * is what lets an in-place downgrade keep the user's wallets, contacts, notes and
 * history. These tests pin that down: the schema constants must not drift without
 * a deliberate bump, and both older and newer files must decode under the
 * production JSON settings (`ignoreUnknownKeys = true`).
 */
class DataCompatTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val released = listOf(
        "1.0.0", "1.1.0", "1.1.1", "2.0.0", "2.1.0", "2.1.1",
        "2.1.2", "2.2.0", "2.2.1", "2.3.0", "2.3.1", "2.3.2",
        "2.4.0", "2.4.1", "2.5.0", "2.5.1", "2.5.2", "2.5.3",
    )

    @Test
    fun `schema versions are pinned until a migration bumps them`() {
        assertEquals("bump this and add a migration if any persisted format changes", 3, SchemaCompat.DB_VERSION)
        assertEquals(1, SchemaCompat.VAULT_VERSION)
        assertEquals(1, SchemaCompat.REGISTRY_VERSION)
    }

    @Test
    fun `every released version can read the data the current app writes`() {
        for (v in released) assertTrue("$v should read current data", SchemaCompat.canReadCurrentData(v))
        assertFalse(SchemaCompat.canReadCurrentData("0.9.0"))
    }

    @Test
    fun `the self-update floor is 1_1_0`() {
        assertFalse(SchemaCompat.canSelfUpdate("1.0.0"))
        assertTrue(SchemaCompat.canSelfUpdate("1.1.0"))
        assertTrue(SchemaCompat.canSelfUpdate("2.3.2"))
    }

    @Test
    fun `a registry index written by v1_0_0 still reads`() {
        val old = """{"version":1,"wallets":[{"id":"a","name":"Old","network":"mainnet","createdAt":1,"vaultFile":"vault.json","dbName":"wallet.db","keystoreAlias":"pocketprl.bio.dek"}],"activeId":"a"}"""
        val list = json.decodeFromString(WalletList.serializer(), old)
        assertEquals(1, list.wallets.size)
        assertEquals("a", list.activeId)
        assertEquals("Old", list.wallets.single().name)
    }

    @Test
    fun `a registry index written by a newer build still reads`() {
        val newer = """{"version":1,"wallets":[{"id":"a","name":"New","network":"mainnet","createdAt":1,"vaultFile":"v","dbName":"d","keystoreAlias":"k","someFutureField":true}],"activeId":"a","anotherFutureField":42}"""
        val list = json.decodeFromString(WalletList.serializer(), newer)
        assertEquals(1, list.wallets.size)
        assertEquals("a", list.activeId)
    }

    @Test
    fun `a registry index missing the optional active id decodes`() {
        val noActive = """{"version":1,"wallets":[]}"""
        assertNull(json.decodeFromString(WalletList.serializer(), noActive).activeId)
    }
}
