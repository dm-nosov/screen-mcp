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
    private var stationary = true // GPS says we are not moving -> don't integrate accel
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
        stationary = true
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
     * Drives the speed filter (only while moving) and the launch trace (always).
     */
    fun onLinearAcceleration(x: Float, y: Float, z: Float, eventNanos: Long) = synchronized(lock) {
        if (status == TrackingStatus.IDLE) return@synchronized

        // Only feed the speed filter when GPS confirms we are moving AND we have a
        // heading to project onto. Standing still, `projectedAccel` is 0, so hand
        // jitter can no longer integrate into a phantom speed.
        val canPredict = !stationary && haveRotation && !bearingRad.isNaN()
        if (canPredict && lastAccelNanos != 0L) {
            val dt = (eventNanos - lastAccelNanos) / 1_000_000_000f
            fusion.predict(projectedAccel(x, y, z), dt)
        }
        lastAccelNanos = eventNanos

        // The trace shows the raw longitudinal signal regardless, so you can still
        // see the launch shape build even before GPS has confirmed the move.
        val traceValue = traceAccel(x, y, z)
        peakAccel = max(peakAccel, abs(traceValue))
        if (trace.size >= TRACE_CAPACITY) trace.removeFirst()
        trace.addLast(traceValue)
    }

    /** A fresh GPS fix. Anchors the fused speed and grows the honest distance. */
    fun onLocation(location: Location, nowElapsedMs: Long) = synchronized(lock) {
        if (status == TrackingStatus.IDLE) return@synchronized

        lastAccuracy = if (location.hasAccuracy()) location.accuracy else 0f
        hasFix = true
        if (status == TrackingStatus.ACQUIRING) status = TrackingStatus.TRACKING

        val gpsSpeed = if (location.hasSpeed()) location.speed else 0f
        stationary = !SpeedFusion.isMoving(gpsSpeed)

        if (stationary) {
            // Zero-velocity update: collapse any drift and drop the now-stale
            // heading so we stop projecting accelerometer noise onto it.
            fusion.zeroVelocityUpdate()
            bearingRad = Float.NaN
        } else {
            fusion.correct(gpsSpeed)
            if (location.hasBearing()) {
                bearingRad = Math.toRadians(location.bearing.toDouble()).toFloat()
            }
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
     * Acceleration along the direction of travel, for the speed filter.
     *
     * Rotate the device-frame sample into the world frame (east/north/up) and take
     * the component along the GPS course. Returns 0 when we lack a rotation matrix
     * or a valid course: without a real heading the projection would just be
     * rectified noise, which is exactly what used to integrate into phantom speed.
     * The caller only invokes this while moving, so 0 here means "let GPS drive".
     */
    private fun projectedAccel(x: Float, y: Float, z: Float): Float {
        if (!haveRotation || bearingRad.isNaN()) return 0f
        // world = R * device;  world axes: X=east, Y=north, Z=up
        val we = rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z
        val wn = rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z
        return we * sin(bearingRad) + wn * cos(bearingRad) // course unit vector (east, north)
    }

    /**
     * Raw longitudinal signal for the launch-trace display only. When we have a
     * course we show the projected component (signed, so braking dips down);
     * otherwise the horizontal magnitude, which shapes the trace but never feeds
     * the speed estimate.
     */
    private fun traceAccel(x: Float, y: Float, z: Float): Float {
        if (haveRotation) {
            val we = rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z
            val wn = rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z
            if (!bearingRad.isNaN()) return we * sin(bearingRad) + wn * cos(bearingRad)
            return sqrt(we * we + wn * wn)
        }
        return sqrt(x * x + y * y)
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
