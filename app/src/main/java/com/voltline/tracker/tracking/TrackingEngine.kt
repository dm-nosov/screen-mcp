package com.voltline.tracker.tracking

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Process-wide singleton that turns raw sensor and location callbacks into a
 * published [TrackState]. The [TrackingService] owns the OS registrations and
 * feeds this object; the UI only ever reads [state].
 *
 * All mutation goes through the [lock] because accelerometer callbacks arrive on
 * the sensor thread while location callbacks arrive on the main thread.
 */
object TrackingEngine {

    private const val TRACE_CAPACITY = 250 // ~5 s of launch history at 50 Hz
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val GPS_ACCURACY_GATE_M = 25f // ignore fixes worse than this for distance

    private val lock = Any()
    private val fusion = SpeedFusion()

    private val _state = MutableStateFlow(TrackState())
    val state: StateFlow<TrackState> = _state.asStateFlow()

    // --- session accumulators (guarded by lock) ---
    private var status = TrackingStatus.IDLE
    private var startElapsedMs = 0L
    private var movingMs = 0L
    private var distanceM = 0.0
    private var maxSpeedMps = 0f
    private var peakAccel = 0f
    private var hasFix = false
    private var lastAccuracy = 0f

    private var lastLocation: Location? = null
    private var lastAccelNanos = 0L

    // World-frame orientation, refreshed by the rotation-vector sensor.
    private val rotationMatrix = FloatArray(9)
    private var haveRotation = false
    private var bearingRad = Float.NaN // travel direction, from GPS course

    private val trace = ArrayDeque<Float>(TRACE_CAPACITY)
    private val csvRows = ArrayList<CsvRow>()

    data class CsvRow(
        val elapsedS: Float,
        val lat: Double,
        val lon: Double,
        val gpsSpeedMps: Float,
        val fusedSpeedMps: Float,
        val distanceM: Float,
        val longAccel: Float,
    )

    fun start(nowElapsedMs: Long) = synchronized(lock) {
        fusion.reset()
        status = TrackingStatus.ACQUIRING
        startElapsedMs = nowElapsedMs
        movingMs = 0L
        distanceM = 0.0
        maxSpeedMps = 0f
        peakAccel = 0f
        hasFix = false
        lastAccuracy = 0f
        lastLocation = null
        lastAccelNanos = 0L
        haveRotation = false
        bearingRad = Float.NaN
        trace.clear()
        csvRows.clear()
        publishLocked(nowElapsedMs)
    }

    fun stop(nowElapsedMs: Long) = synchronized(lock) {
        status = TrackingStatus.IDLE
        publishLocked(nowElapsedMs)
    }

    fun isActive(): Boolean = synchronized(lock) { status != TrackingStatus.IDLE }

    /** Latest device->world rotation matrix from the rotation-vector sensor. */
    fun onRotationMatrix(matrix: FloatArray) = synchronized(lock) {
        System.arraycopy(matrix, 0, rotationMatrix, 0, 9)
        haveRotation = true
    }

    /**
     * A gravity-free acceleration sample in the device frame (TYPE_LINEAR_ACCELERATION).
     * Returns the longitudinal component it derived, purely so the service can log it.
     */
    fun onLinearAcceleration(x: Float, y: Float, z: Float, eventNanos: Long) = synchronized(lock) {
        if (status == TrackingStatus.IDLE) return@synchronized
        val aLong = longitudinalAccel(x, y, z)

        if (lastAccelNanos != 0L) {
            val dt = (eventNanos - lastAccelNanos) / 1_000_000_000f
            fusion.predict(aLong, dt)
        }
        lastAccelNanos = eventNanos

        peakAccel = max(peakAccel, abs(aLong))
        if (trace.size >= TRACE_CAPACITY) trace.removeFirst()
        trace.addLast(aLong)
    }

    /** A fresh GPS fix. Anchors the fused speed and grows the honest distance. */
    fun onLocation(location: Location, nowElapsedMs: Long) = synchronized(lock) {
        if (status == TrackingStatus.IDLE) return@synchronized

        lastAccuracy = if (location.hasAccuracy()) location.accuracy else 0f
        hasFix = true
        if (status == TrackingStatus.ACQUIRING) status = TrackingStatus.TRACKING

        val gpsSpeed = if (location.hasSpeed()) location.speed else 0f
        fusion.correct(gpsSpeed)

        if (location.hasBearing() && gpsSpeed >= SpeedFusion.MOVING_THRESHOLD_MPS) {
            bearingRad = Math.toRadians(location.bearing.toDouble()).toFloat()
        }

        val fused = fusion.speed
        maxSpeedMps = max(maxSpeedMps, fused)

        val prev = lastLocation
        if (prev != null) {
            val stepM = haversine(prev, location)
            val goodFix = lastAccuracy in 0.001f..GPS_ACCURACY_GATE_M
            // Only accumulate real movement from trustworthy fixes.
            if (goodFix && SpeedFusion.isMoving(gpsSpeed) && stepM < 60.0) {
                distanceM += stepM
            }
        }
        lastLocation = Location(location)

        val elapsed = nowElapsedMs - startElapsedMs
        if (SpeedFusion.isMoving(fused)) {
            movingMs += 1000L // fixes arrive ~1 Hz
        }

        csvRows.add(
            CsvRow(
                elapsedS = elapsed / 1000f,
                lat = location.latitude,
                lon = location.longitude,
                gpsSpeedMps = gpsSpeed,
                fusedSpeedMps = fused,
                distanceM = distanceM.toFloat(),
                longAccel = if (trace.isEmpty()) 0f else trace.last(),
            ),
        )
        publishLocked(nowElapsedMs)
    }

    /** Rebuild and emit the public snapshot. Called on the publish ticker and on events. */
    fun publish(nowElapsedMs: Long) = synchronized(lock) { publishLocked(nowElapsedMs) }

    fun snapshotCsv(): List<CsvRow> = synchronized(lock) { ArrayList(csvRows) }

    private fun publishLocked(nowElapsedMs: Long) {
        val elapsed = if (status == TrackingStatus.IDLE) _state.value.elapsedMs
        else (nowElapsedMs - startElapsedMs).coerceAtLeast(0L)
        val avgMps = if (movingMs > 0) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
        _state.value = TrackState(
            status = status,
            speedKmh = fusion.speed * 3.6f,
            maxSpeedKmh = maxSpeedMps * 3.6f,
            avgSpeedKmh = avgMps * 3.6f,
            distanceKm = (distanceM / 1000.0).toFloat(),
            elapsedMs = elapsed,
            gpsAccuracyM = lastAccuracy,
            hasGpsFix = hasFix,
            accelTrace = trace.toList(),
            peakAccel = peakAccel,
        )
    }

    /**
     * Project device-frame linear acceleration onto the direction of travel.
     *
     * With a rotation matrix we rotate into the world frame (east/north/up) and
     * take the component along the GPS course. Without orientation or a course we
     * fall back to horizontal magnitude, signed by whether the phone is
     * accelerating — good enough to shape the launch trace, and the GPS
     * correction cleans up any bias.
     */
    private fun longitudinalAccel(x: Float, y: Float, z: Float): Float {
        if (haveRotation) {
            // world = R * device;  world axes: X=east, Y=north, Z=up
            val we = rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z
            val wn = rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z
            return if (!bearingRad.isNaN()) {
                // course unit vector in (east, north)
                we * sin(bearingRad) + wn * cos(bearingRad)
            } else {
                val mag = sqrt(we * we + wn * wn)
                // sign it by the dominant horizontal axis so the trace has shape
                if (atan2(wn, we) >= 0) mag else -mag
            }
        }
        return sqrt(x * x + y * y) // last-resort horizontal magnitude
    }

    private fun haversine(a: Location, b: Location): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }
}
