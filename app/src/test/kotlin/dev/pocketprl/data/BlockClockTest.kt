package dev.pocketprl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockClockTest {
    @Test
    fun `no estimate until the window spans enough blocks`() {
        var c = BlockClock.load(null)
        assertNull(c.secondsPerBlock)
        c = c.add(100, 1_000).add(101, 1_120).add(102, 1_240)
        assertNull(c.secondsPerBlock)
        c = c.add(103, 1_360)
        assertEquals(120L, c.secondsPerBlock)
    }

    @Test
    fun `interval is the span over the window, so one odd block barely moves it`() {
        var c = BlockClock.load(null)
        var t = 0L
        for (h in 1..20L) { t += if (h == 10L) 900 else 100; c = c.add(h, t) }
        // 19 intervals: 18 x 100 s + one 900 s outlier = 2700 / 19
        assertEquals(142L, c.secondsPerBlock)
    }

    @Test
    fun `repeat tips and reorgs are ignored, window is capped`() {
        var c = BlockClock.load(null).add(5, 500).add(5, 999).add(4, 400)
        assertEquals("5:500", c.save())
        for (h in 6..60L) c = c.add(h, h * 100)
        assertEquals(30, c.save().split(',').size)
        assertEquals(100L, c.secondsPerBlock)
    }

    @Test
    fun `survives a round trip through the meta table`() {
        val saved = BlockClock.load(null).add(1, 100).add(2, 250).add(3, 400).add(4, 550).save()
        assertEquals(150L, BlockClock.load(saved).secondsPerBlock)
        assertNull(BlockClock.load("garbage,1:x").secondsPerBlock)
    }

    @Test
    fun `parses blockbook timestamps with and without fractions or offsets`() {
        assertEquals(1_700_000_000L, BlockClock.parseTime("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000L, BlockClock.parseTime("2023-11-14T22:13:20.123456789Z"))
        assertEquals(1_700_000_000L, BlockClock.parseTime("2023-11-15T00:13:20+02:00"))
        assertNull(BlockClock.parseTime(""))
    }
}
