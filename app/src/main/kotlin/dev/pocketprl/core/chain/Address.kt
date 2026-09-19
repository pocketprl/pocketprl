package dev.pocketprl.core.chain

import dev.pocketprl.core.crypto.Bech32
import dev.pocketprl.core.crypto.Taproot

/** Reason an address failed to parse; maps to a localized message in the UI. */
enum class AddressError { ENTER, INVALID, UNKNOWN_PREFIX, ONLY_TAPROOT, INVALID_LENGTH, WRONG_NETWORK }

/** Pearl only supports Taproot (witness v1, 32-byte program) addresses. */
object Address {
    class Parsed(val network: Network, val outputKey: ByteArray) {
        val scriptPubKey: ByteArray get() = Taproot.p2trScript(outputKey)
    }

    sealed class Result {
        data class Valid(val parsed: Parsed) : Result()
        data class Invalid(
            val error: AddressError,
            /** English fallback for callers that cannot reach Android resources. */
            val reason: String,
            val arg1: String? = null,
            val arg2: String? = null,
        ) : Result()
    }

    fun encode(network: Network, outputKey: ByteArray): String = Bech32.encodeSegwit(network.hrp, 1, outputKey)

    fun parse(raw: String, expected: Network? = null): Result {
        val s = raw.trim()
        if (s.isEmpty()) return Result.Invalid(AddressError.ENTER, "Enter a recipient address")
        val d = Bech32.decodeSegwit(s) ?: return Result.Invalid(AddressError.INVALID, "Not a valid Pearl address")
        val net = Network.entries.firstOrNull { it.hrp == d.hrp }
            ?: return Result.Invalid(AddressError.UNKNOWN_PREFIX, "Unknown address prefix \"${d.hrp}\"", d.hrp)
        if (d.witnessVersion != 1) return Result.Invalid(AddressError.ONLY_TAPROOT, "Only Taproot (${net.hrp}1p…) addresses are supported", net.hrp)
        if (d.program.size != 32) return Result.Invalid(AddressError.INVALID_LENGTH, "Invalid Taproot address length")
        if (expected != null && net != expected) {
            return Result.Invalid(AddressError.WRONG_NETWORK, "That is a ${net.displayName} address; this wallet is on ${expected.displayName}", net.displayName, expected.displayName)
        }
        return Result.Valid(Parsed(net, d.program))
    }

    fun isValid(raw: String, expected: Network? = null): Boolean = parse(raw, expected) is Result.Valid

    fun fromScript(network: Network, script: ByteArray): String? =
        if (Taproot.isP2tr(script)) encode(network, script.copyOfRange(2, 34)) else null

    fun short(address: String, head: Int = 10, tail: Int = 6): String =
        if (address.length <= head + tail + 1) address else address.take(head) + "…" + address.takeLast(tail)
}
