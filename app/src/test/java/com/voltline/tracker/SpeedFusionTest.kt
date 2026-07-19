package com.voltline.tracker

import com.voltline.tracker.tracking.SpeedFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedFusionTest {

    @Test
    fun `speed integrates acceleration over time`() {
        val f = SpeedFusion()
        // 2 m/s^2 for 1 s in 50 steps -> ~2 m/s
        repeat(50) { f.predict(2f, 0.02f) }
        assertEquals(2f, f.speed, 1e-3f)
    }

    @Test
    fun `speed never goes negative`() {
        val f = SpeedFusion()
        f.predict(-5f, 0.5f)
        assertEquals(0f, f.speed, 0f)
    }

    @Test
    fun `absurd dt is ignored`() {
        val f = SpeedFusion()
        f.predict(3f, 5f) // gap too large (backgrounded), must not corrupt state
        assertEquals(0f, f.speed, 0f)
    }

    @Test
    fun `gps correction pulls estimate toward measurement`() {
        val f = SpeedFusion()
        // Drift the estimate high, then let a lower GPS reading rein it in.
        repeat(100) { f.predict(2f, 0.02f) } // ~4 m/s
        val before = f.speed
        f.correct(2f)
        assertTrue("correction should reduce an overshoot", f.speed < before)
        assertTrue("but not overshoot past the measurement", f.speed > 2f)
    }

    @Test
    fun `repeated corrections converge to gps truth`() {
        val f = SpeedFusion()
        repeat(30) { f.correct(8f) }
        assertEquals(8f, f.speed, 0.1f)
    }

    @Test
    fun `moving threshold rejects standstill noise`() {
        assertTrue(SpeedFusion.isMoving(1.0f))
        assertTrue(!SpeedFusion.isMoving(0.2f))
    }
}
