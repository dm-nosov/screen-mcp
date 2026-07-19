package com.voltline.tracker.tracking

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.voltline.tracker.MainActivity
import com.voltline.tracker.R
import com.voltline.tracker.VoltlineApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the sensors and GPS running while the screen is
 * off and the phone is in a pocket. It registers TYPE_LINEAR_ACCELERATION at game
 * rate plus the rotation vector, requests 1 Hz high-accuracy location, and feeds
 * every callback into [TrackingEngine].
 */
class TrackingService : LifecycleService() {

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocation: FusedLocationProviderClient
    private val rotationMatrix = FloatArray(9)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION ->
                    TrackingEngine.onLinearAcceleration(
                        event.values[0], event.values[1], event.values[2], event.timestamp,
                    )

                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    TrackingEngine.onRotationMatrix(rotationMatrix)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            TrackingEngine.onLocation(location, SystemClock.elapsedRealtime())
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }

            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (TrackingEngine.isActive()) return
        startForegroundCompat()
        TrackingEngine.start(SystemClock.elapsedRealtime())

        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        requestLocation()
        startPublishTicker()
    }

    private fun requestLocation() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fusedLocation.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            // Permission revoked mid-session; the engine simply stays in ACQUIRING.
        }
    }

    /** Republishes an elapsed-time-updated snapshot at 10 Hz so the UI stays live. */
    private fun startPublishTicker() {
        lifecycleScope.launch {
            while (isActive && TrackingEngine.isActive()) {
                TrackingEngine.publish(SystemClock.elapsedRealtime())
                delay(100L)
            }
        }
    }

    private fun stopTracking() {
        sensorManager.unregisterListener(sensorListener)
        fusedLocation.removeLocationUpdates(locationCallback)
        TrackingEngine.stop(SystemClock.elapsedRealtime())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(sensorListener)
        fusedLocation.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, VoltlineApp.TRACKING_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat_voltline)
            .setOngoing(true)
            .setContentIntent(content)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.voltline.tracker.STOP"
        private const val NOTIF_ID = 42
    }
}
