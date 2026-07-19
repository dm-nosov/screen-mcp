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

    @Test
    fun `zero-velocity update collapses accumulated drift`() {
        val f = SpeedFusion()
        repeat(200) { f.predict(1.5f, 0.02f) } // drift up to ~6 m/s
        assertTrue(f.speed > 3f)
        f.zeroVelocityUpdate()
        assertEquals(0f, f.speed, 0f)
    }

    @Test
    fun `standing still with positive accel bias does not creep`() {
        // Reproduces the reported bug: GPS says stationary (ZUPT each 1 Hz fix)
        // while a positive accel bias would otherwise integrate. Because the engine
        // does not predict while stationary, the only input is the ZUPT -> stays 0.
        val f = SpeedFusion()
        repeat(30) { // 30 seconds of standing still
            // engine gate: stationary -> no predict() calls at all this second
            f.zeroVelocityUpdate() // the 1 Hz GPS fix
        }
        assertEquals(0f, f.speed, 0f)
    }
}
