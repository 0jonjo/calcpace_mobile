package app.calcpace.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Turns on every source [DiagLog] records, from a run's Start to its Stop,
 * pauses included (what the runner does while paused is part of the study):
 * the fused provider, the GNSS status, the step counter, the barometer,
 * activity recognition and the screen, power and battery broadcasts. The
 * GPS itself is the service's, which hands each fix to [DiagLog].
 *
 * A source the phone lacks, or a permission refused, becomes a NOTE and the
 * rest go on: this must never get in the way of the recording.
 */
class DiagProbe(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private var started = false

    private var firstSteps: Float? = null
    private var lastSteps: Float? = null
    private var lastStepLog = 0L
    private var lastPressureLog = 0L
    private var lastGnssLog = 0L
    private var lastBattery: String? = null

    fun start() {
        if (started) return
        started = true
        startFused()
        startGnss()
        startSensors()
        startActivityRecognition()
        startBroadcasts()
        battery(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        val power = context.getSystemService(PowerManager::class.java)
        DiagLog.write("SCREEN", if (power.isInteractive) "on" else "off")
        if (power.isPowerSaveMode) DiagLog.write("POWER", "save_on")
    }

    @SuppressLint("MissingPermission") // removeActivityUpdates: checked just below
    fun stop() {
        if (!started) return
        started = false
        lastSteps?.let { steps -> DiagLog.write("STEP", (steps - (firstSteps ?: steps)).toInt()) }
        fused.removeLocationUpdates(fusedCallback)
        locations.unregisterGnssStatusCallback(gnssCallback)
        sensors.unregisterListener(sensorListener)
        if (hasActivityPermission()) {
            runCatching { ActivityRecognition.getClient(context).removeActivityUpdates(activityIntent()) }
        }
        runCatching { context.unregisterReceiver(broadcasts) }
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { DiagLog.location("FUSED", it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFused() {
        if (!hasLocation()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
            .addOnFailureListener { DiagLog.write("NOTE", "fused unavailable: ${it.message}") }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val now = System.currentTimeMillis()
            if (now - lastGnssLog < GNSS_EVERY_MS) return
            lastGnssLog = now
            val used = (0 until status.satelliteCount).filter { status.usedInFix(it) }.map { status.getCn0DbHz(it) }
            DiagLog.write("GNSS", status.satelliteCount, used.size, used.average().takeIf { used.isNotEmpty() }, used.maxOrNull())
        }

        override fun onStarted() = DiagLog.write("NOTE", "gnss started")
        override fun onStopped() = DiagLog.write("NOTE", "gnss stopped")
    }

    @SuppressLint("MissingPermission")
    private fun startGnss() {
        if (!hasLocation()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locations.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssCallback)
        } else {
            @Suppress("DEPRECATION")
            locations.registerGnssStatusCallback(gnssCallback, main)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = System.currentTimeMillis()
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val steps = event.values[0]
                    if (firstSteps == null) firstSteps = steps
                    lastSteps = steps
                    if (now - lastStepLog >= SENSOR_EVERY_MS) {
                        lastStepLog = now
                        DiagLog.write("STEP", (steps - firstSteps!!).toInt())
                    }
                }
                Sensor.TYPE_PRESSURE -> if (now - lastPressureLog >= SENSOR_EVERY_MS) {
                    lastPressureLog = now
                    DiagLog.write("PRES", event.values[0])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun startSensors() {
        val steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        when {
            steps == null -> DiagLog.write("NOTE", "no step counter")
            !hasActivityPermission() -> DiagLog.write("NOTE", "no physical activity permission: no steps")
            else -> sensors.registerListener(sensorListener, steps, SensorManager.SENSOR_DELAY_NORMAL, main)
        }
        val pressure = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressure == null) DiagLog.write("NOTE", "no barometer")
        else sensors.registerListener(sensorListener, pressure, SensorManager.SENSOR_DELAY_NORMAL, main)
    }

    @SuppressLint("MissingPermission")
    private fun startActivityRecognition() {
        if (!hasActivityPermission()) {
            DiagLog.write("NOTE", "no physical activity permission: no activity recognition")
            return
        }
        ActivityRecognition.getClient(context).requestActivityUpdates(ACTIVITY_EVERY_MS, activityIntent())
            .addOnFailureListener { DiagLog.write("NOTE", "activity recognition unavailable: ${it.message}") }
    }

    // Mutable: Google Play services fills the result into it.
    private fun activityIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, DiagActivityReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private val broadcasts = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> DiagLog.write("SCREEN", "on")
                Intent.ACTION_SCREEN_OFF -> DiagLog.write("SCREEN", "off")
                Intent.ACTION_USER_PRESENT -> DiagLog.write("SCREEN", "unlocked")
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> DiagLog.write(
                    "POWER", if (context.getSystemService(PowerManager::class.java).isPowerSaveMode) "save_on" else "save_off",
                )
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> DiagLog.write(
                    "POWER", if (context.getSystemService(PowerManager::class.java).isDeviceIdleMode) "idle_on" else "idle_off",
                )
                Intent.ACTION_BATTERY_CHANGED -> battery(intent)
            }
        }
    }

    private fun startBroadcasts() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(context, broadcasts, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ACTION_BATTERY_CHANGED repeats for voltage and temperature: log a change
    // of level, charging or thermal status only.
    private fun battery(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        } else {
            null
        }
        val reading = "${level * 100 / scale},$charging,$thermal"
        if (reading == lastBattery) return
        lastBattery = reading
        DiagLog.write("BATT", level * 100 / scale, charging, thermal)
    }

    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasActivityPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACTIVITY_RECOGNITION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SENSOR_EVERY_MS = 1_000L
        const val GNSS_EVERY_MS = 2_000L
        const val ACTIVITY_EVERY_MS = 5_000L
    }
}

/** Activity recognition's answers, delivered by Google Play services. */
class DiagActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val activity = ActivityRecognitionResult.extractResult(intent)?.mostProbableActivity ?: return
        DiagLog.write("ACT", name(activity.type), activity.confidence)
    }

    private fun name(type: Int) = when (type) {
        DetectedActivity.STILL -> "still"
        DetectedActivity.WALKING -> "walking"
        DetectedActivity.RUNNING -> "running"
        DetectedActivity.ON_FOOT -> "on_foot"
        DetectedActivity.ON_BICYCLE -> "bicycle"
        DetectedActivity.IN_VEHICLE -> "vehicle"
        DetectedActivity.TILTING -> "tilting"
        else -> "unknown"
    }
}
