package com.voltline.tracker.tracking

import kotlin.math.abs

/**
 * Scalar complementary filter fusing a fast, drift-prone accelerometer stream
 * with a slow, absolute GPS speed.
 *
 * The accelerometer [predict]s sub-second changes at sensor rate; every GPS fix
 * [correct]s the estimate back toward truth, which is what keeps the double
 * integration from running away. It is deliberately a first-order filter rather
 * than a full Kalman: on a phone the process/measurement covariances are not
 * knowable well enough for a Kalman gain to beat a well-chosen fixed blend.
 */
class SpeedFusion {

    /** Fused ground speed in m/s. */
    var speed = 0f
        private set

    /**
     * Blend factor applied on each GPS fix. 1.0 = trust GPS completely (no
     * accelerometer contribution survives a fix), 0.0 = ignore GPS. 0.35 keeps
     * the accelerometer detail between fixes while snapping out drift each second.
     */
    private val correctionGain = 0.35f

    /**
     * Advance the estimate using longitudinal acceleration [aLong] (m/s^2, along
     * the direction of travel) over [dt] seconds. Speed cannot go negative.
     */
    fun predict(aLong: Float, dt: Float) {
        if (dt <= 0f || dt > 0.5f) return // ignore absurd gaps (backgrounding, first sample)
        speed = (speed + aLong * dt).coerceAtLeast(0f)
    }

    /** Pull the estimate toward an absolute GPS speed measurement (m/s). */
    fun correct(gpsSpeed: Float) {
        speed = (speed + correctionGain * (gpsSpeed - speed)).coerceAtLeast(0f)
    }

    /**
     * Zero-velocity update. GPS reports we are stationary, so collapse whatever
     * drift the accelerometer integrated between fixes. Without this, rectified
     * sensor/hand noise integrates into a phantom speed that a fractional GPS
     * correction can never fully claw back — the classic standing-still creep.
     */
    fun zeroVelocityUpdate() {
        speed = 0f
    }

    fun reset() {
        speed = 0f
    }

    companion object {
        /** Below this the reading is treated as sensor noise / standing still. */
        const val MOVING_THRESHOLD_MPS = 0.6f

        fun isMoving(speedMps: Float) = abs(speedMps) >= MOVING_THRESHOLD_MPS
    }
}
