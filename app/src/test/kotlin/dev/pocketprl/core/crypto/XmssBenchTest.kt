package dev.pocketprl.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class XmssBenchTest {
    @Test
    fun keygenTiming() {
        val priv = ByteArray(64) { it.toByte() }
        val pub = ByteArray(32) { (it * 3).toByte() }
        Xmss.keygen(priv, pub) // warm up JIT
        val t0 = System.nanoTime()
        val a = Xmss.keygen(priv, pub, parallel = false)
        val t1 = System.nanoTime()
        val b = Xmss.keygen(priv, pub, parallel = true)
        val t2 = System.nanoTime()
        assertEquals(a.toHex(), b.toHex())
        println("XMSS keygen: serial ${(t1 - t0) / 1_000_000} ms, parallel ${(t2 - t1) / 1_000_000} ms (${Runtime.getRuntime().availableProcessors()} cores)")
    }
}
