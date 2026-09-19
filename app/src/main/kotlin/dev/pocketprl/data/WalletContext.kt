package dev.pocketprl.data

import android.content.Context
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.blockbook.BlockbookApi
import dev.pocketprl.data.db.WalletDb
import dev.pocketprl.data.price.PriceApi
import dev.pocketprl.data.vault.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Everything that belongs to one wallet: its vault, unlock session, database,
 * indexer client and repository. Wallets never share any of these, so two
 * wallets on different networks or with different passwords coexist cleanly.
 */
class WalletContext(
    val entry: WalletEntry,
    appContext: Context,
    settings: Settings,
    userAgent: String,
    priceApi: PriceApi,
) : AutoCloseable {
    val id: String get() = entry.id
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val vault = KeyVault(appContext, entry.vaultFile, entry.keystoreAlias)
    val session = Session(vault)
    val db = WalletDb(appContext, entry.dbName)
    val api = BlockbookApi(
        baseUrlProvider = { settings.blockbookUrl(vault.network ?: Network.fromId(entry.network)) },
        userAgent = userAgent,
    )
    val repository = WalletRepository(db, api, session, vault, settings, scope, appContext, priceApi)

    override fun close() {
        session.lock()
        scope.cancel()
        db.close()
    }
}
