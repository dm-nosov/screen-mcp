package com.voltline.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voltline.tracker.data.CsvExporter
import com.voltline.tracker.tracking.TrackingEngine
import com.voltline.tracker.tracking.TrackingService
import com.voltline.tracker.ui.TrackerScreen
import com.voltline.tracker.ui.theme.VoltBackground
import com.voltline.tracker.ui.theme.VoltlineTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            launchService()
        } else {
            toast(getString(R.string.needs_location))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoltlineTheme {
                val state by TrackingEngine.state.collectAsStateWithLifecycle()
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(VoltBackground)
                        .systemBarsPadding(),
                    color = VoltBackground,
                ) {
                    TrackerScreen(
                        state = state,
                        onStart = ::onStartClicked,
                        onStop = ::onStopClicked,
                        onExport = ::onExportClicked,
                    )
                }
            }
        }
    }

    private fun onStartClicked() {
        if (hasLocationPermission()) launchService() else requestPermissions()
    }

    private fun onStopClicked() {
        startService(Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
    }

    private fun onExportClicked() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val result = CsvExporter.export(this, stamp)
        toast(
            if (result == null) getString(R.string.export_empty)
            else getString(R.string.export_done, result.rowCount, result.displayPath),
        )
    }

    private fun launchService() {
        val intent = Intent(this, TrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
