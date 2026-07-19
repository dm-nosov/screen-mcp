package com.voltline.tracker.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.voltline.tracker.tracking.TrackingEngine
import java.io.File

/** Writes the current session's per-fix log to a CSV the user can pull off the device. */
object CsvExporter {

    private const val HEADER =
        "elapsed_s,lat,lon,gps_speed_mps,fused_speed_mps,distance_m,long_accel_mps2\n"

    data class Result(val displayPath: String, val rowCount: Int)

    fun export(context: Context, fileStamp: String): Result? {
        val rows = TrackingEngine.snapshotCsv()
        if (rows.isEmpty()) return null

        val name = "voltline_session_$fileStamp.csv"
        val body = buildString {
            append(HEADER)
            for (r in rows) {
                append(r.elapsedS).append(',')
                append(r.lat).append(',')
                append(r.lon).append(',')
                append(r.gpsSpeedMps).append(',')
                append(r.fusedSpeedMps).append(',')
                append(r.distanceM).append(',')
                append(r.longAccel).append('\n')
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToDownloads(context, name, body)?.let { Result(it, rows.size) }
        } else {
            writeToAppDir(context, name, body)?.let { Result(it, rows.size) }
        }
    }

    private fun writeToDownloads(context: Context, name: String, body: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Voltline")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/Voltline/$name"
    }

    private fun writeToAppDir(context: Context, name: String, body: String): String? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val file = File(dir, name)
        file.writeText(body)
        return file.absolutePath
    }
}
