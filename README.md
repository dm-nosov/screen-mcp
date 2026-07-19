# Voltline

**Electric inline telemetry.** A native Android app that tracks distance, speed,
and launch acceleration for electric inline skates.

<p align="center"><em>Speed you can trust, a launch trace you can tune against.</em></p>

## What it does

- **Live speed** fused from GPS and the accelerometer, so the readout is both
  absolute (GPS-anchored) and responsive (accelerometer fills the sub-second
  detail between fixes).
- **Distance** measured from GPS geometry (haversine between good fixes) so the
  total stays honest even when the fused speed briefly overshoots.
- **Top / average speed and elapsed moving time** per session.
- **Launch trace** — a high-rate longitudinal-acceleration waveform. This is the
  thing native buys you over a web app: an uncapped sensor stream at
  `SENSOR_DELAY_GAME` (~50 Hz) makes the breakaway spike legible, so you can
  profile the launch and tune the ramp against real data instead of carpet-feel.
- **Foreground service** so logging keeps running with the screen off and the
  phone in your pocket.
- **CSV export** of the full per-fix log to `Downloads/Voltline/` (scoped
  storage, no broad storage permission required).

## How the fusion works

A scalar complementary filter (`SpeedFusion`):

1. **Predict** — at sensor rate, integrate the longitudinal component of the
   gravity-free acceleration (`TYPE_LINEAR_ACCELERATION` rotated into the world
   frame via the rotation vector, projected onto the GPS course).
2. **Correct** — on each 1 Hz GPS fix, pull the estimate back toward the
   absolute GPS speed. This correction is what stops the double integration from
   drifting away.

It is deliberately first-order rather than a full Kalman filter: on a phone the
process/measurement covariances aren't knowable well enough for a Kalman gain to
reliably beat a well-chosen fixed blend. The filter degrades gracefully — with
no rotation vector or GPS course, it falls back to horizontal acceleration
magnitude and leans on the GPS correction.

The filter is covered by unit tests in
[`SpeedFusionTest`](app/src/test/java/com/voltline/tracker/SpeedFusionTest.kt).

## Project layout

```
app/src/main/java/com/voltline/tracker/
├── MainActivity.kt              # permissions, service control, Compose host
├── VoltlineApp.kt               # notification channel
├── tracking/
│   ├── TrackingEngine.kt        # sensor/GPS callbacks -> published TrackState
│   ├── TrackingService.kt       # foreground service, sensor + location registration
│   ├── SpeedFusion.kt           # complementary filter (pure, unit-tested)
│   └── TrackState.kt            # immutable UI snapshot
├── data/CsvExporter.kt          # session log -> CSV in Downloads
└── ui/                          # Compose cockpit + launch-trace chart
```

## Build

```bash
./gradlew :app:assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # run the fusion unit tests
```

Requires the Android SDK (`compileSdk 35`) and JDK 17. Open the folder in
Android Studio, or build from the CLI with the bundled Gradle wrapper.

- **minSdk** 26 (Android 8.0) · **targetSdk** 35
- Kotlin 2.0 · Jetpack Compose (Material 3) · Play Services Location

## Permissions

- `ACCESS_FINE_LOCATION` — speed and distance (requested at first start).
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` — keep tracking with the
  screen off.
- `POST_NOTIFICATIONS` — the ongoing tracking notification (Android 13+).
