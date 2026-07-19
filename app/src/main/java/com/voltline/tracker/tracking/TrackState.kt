package com.voltline.tracker.tracking

/** Lifecycle of a tracking session, driven by [TrackingEngine]. */
enum class TrackingStatus {
    /** Nothing running. */
    IDLE,

    /** Service is up, waiting for the first usable GPS fix. */
    ACQUIRING,

    /** Actively fusing sensors and accumulating a session. */
    TRACKING,
}

/**
 * Immutable snapshot of the current session, published to the UI.
 *
 * Speed is fused (GPS-anchored, accelerometer-smoothed). Distance is derived
 * from GPS geometry so it stays honest even when the fusion overshoots.
 */
data class TrackState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val speedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val distanceKm: Float = 0f,
    val elapsedMs: Long = 0L,
    val gpsAccuracyM: Float = 0f,
    val hasGpsFix: Boolean = false,
    /** Recent longitudinal acceleration (m/s^2), oldest first — the launch trace. */
    val accelTrace: List<Float> = emptyList(),
    /** Peak longitudinal acceleration seen this session (m/s^2). */
    val peakAccel: Float = 0f,
) {
    val peakAccelG: Float get() = peakAccel / 9.80665f
}
