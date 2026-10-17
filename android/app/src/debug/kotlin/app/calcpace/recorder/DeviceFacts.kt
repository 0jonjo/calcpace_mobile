package app.calcpace.recorder

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.calcpace.BuildConfig

/** The phone a test run was made on, and what it let the recorder do: the head of summary.txt. */
object DeviceFacts {
    fun of(context: Context): List<Pair<String, String>> {
        val sensors = context.getSystemService(SensorManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val locations = context.getSystemService(LocationManager::class.java)
        fun has(type: Int) = if (sensors.getDefaultSensor(type) != null) "yes" else "no"
        fun granted(permission: String) =
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) "yes" else "no"

        return buildList {
            add("Phone" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            add("App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add("GNSS chip" to (locations.gnssHardwareModelName ?: "unknown"))
            }
            add("Step counter" to has(Sensor.TYPE_STEP_COUNTER))
            add("Barometer" to has(Sensor.TYPE_PRESSURE))
            add("Location permission (precise)" to granted(Manifest.permission.ACCESS_FINE_LOCATION))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("Physical activity permission" to granted(Manifest.permission.ACTIVITY_RECOGNITION))
            }
            add("Battery optimization off" to if (power.isIgnoringBatteryOptimizations(context.packageName)) "yes" else "no")
            add("Power saving now" to if (power.isPowerSaveMode) "on" else "off")
            add("App standby bucket" to bucket(context.getSystemService(UsageStatsManager::class.java).appStandbyBucket))
        }
    }

    private fun bucket(value: Int) = when (value) {
        // STANDBY_BUCKET_EXEMPTED is hidden API: the battery-optimization whitelist.
        5 -> "exempted"
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
        else -> value.toString()
    }
}
