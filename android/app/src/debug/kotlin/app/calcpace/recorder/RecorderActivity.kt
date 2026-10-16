package app.calcpace.recorder

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.calcpace.main.MainActivity
import kotlinx.coroutines.launch

/**
 * The recording screen (G0, debug builds only, its own launcher icon "Record
 * run"): time, distance and average pace, Start / Pause / Stop, and what the
 * GPS did, so a test run says whether the phone kept the recorder alive.
 * The run is saved as GPX in Downloads/Calcpace; the site's run form takes
 * it from there ("Open Calcpace").
 */
class RecorderActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var distance: TextView
    private lateinit var pace: TextView
    private lateinit var details: TextView
    private lateinit var primary: Button
    private lateinit var stop: Button
    private lateinit var share: Button
    private lateinit var openSite: Button
    private lateinit var battery: Button

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 1000)
        }
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) RecorderService.send(this, RecorderService.ACTION_START)
        else status.text = "Location is off for Calcpace: the recorder needs it to run."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(layout())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecorderService.snapshots.collect { render() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ticker.post(tick)
    }

    override fun onStop() {
        ticker.removeCallbacks(tick)
        super.onStop()
    }

    private fun layout(): LinearLayout {
        fun big(size: Float) = TextView(this).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply { gravity = Gravity.CENTER; textSize = 16f }
        clock = big(64f)
        distance = big(40f)
        pace = big(28f)
        details = TextView(this).apply { textSize = 13f; setPadding(0, 32, 0, 32) }
        primary = Button(this).apply { textSize = 22f; setOnClickListener { onPrimary() } }
        stop = Button(this).apply { text = "Stop and save"; setOnClickListener { confirmStop() } }
        share = Button(this).apply { text = "Share GPX"; setOnClickListener { shareLast() } }
        openSite = Button(this).apply {
            text = "Open Calcpace (upload on the run form)"
            setOnClickListener { startActivity(Intent(this@RecorderActivity, MainActivity::class.java)) }
        }
        battery = Button(this).apply {
            text = "Battery optimization settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            listOf(status, clock, distance, pace, primary, stop, details, share, openSite, battery).forEach(::addView)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(48, 48 + bars.top, 48, 48 + bars.bottom)
                insets
            }
        }
    }

    private fun onPrimary() {
        val live = RecorderService.snapshots.value?.takeIf { it.finished == null }?.track
        when {
            // The service starts again from the log and turns the GPS back
            // on. The minutes it was dead stay on the clock: the runner was
            // running through them.
            live == null && RecorderFiles(this).hasUnfinishedRun() ->
                RecorderService.send(this, RecorderService.ACTION_START)
            live?.isRunning == true -> RecorderService.send(this, RecorderService.ACTION_PAUSE)
            live?.isPaused == true -> RecorderService.send(this, RecorderService.ACTION_RESUME)
            hasLocation() -> RecorderService.send(this, RecorderService.ACTION_START)
            else -> askPermissions.launch(permissions())
        }
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setMessage("Stop the run and save it?")
            .setPositiveButton("Stop") { _, _ -> RecorderService.send(this, RecorderService.ACTION_STOP) }
            .setNegativeButton("Keep going", null)
            .show()
    }

    private fun render() {
        val snapshot = RecorderService.snapshots.value
        val now = System.currentTimeMillis()
        val finished = snapshot?.finished
        // The service is gone but the log says a run was on: the system
        // killed it. Read the log to show where it stopped.
        val orphan = snapshot == null && RecorderFiles(this).hasUnfinishedRun()
        val track = when {
            snapshot != null -> snapshot.track
            orphan -> Track.of(RecorderFiles(this).readCurrent())
            else -> null
        }

        clock.text = RunFormat.clock(track?.movingMillis(now) ?: 0)
        distance.text = RunFormat.km(track?.distanceMeters ?: 0.0)
        pace.text = RunFormat.pace(track?.averagePace(now))

        status.text = when {
            finished != null && finished.name == null -> "Could not save the run. It is kept: tap Start to try again."
            finished?.download != null -> "Saved: Downloads/Calcpace/${finished.name}"
            finished != null -> "Saved in the app only (no copy in Downloads): ${finished.name}"
            orphan -> "The recorder was stopped by the phone. Resume or stop and save."
            track?.isRunning == true -> "Recording · GPS ±${track.lastAccuracy?.toInt() ?: "–"} m"
            track?.isPaused == true -> "Paused"
            else -> "Ready"
        }
        primary.text = when {
            finished != null || track == null -> "Start"
            orphan -> "Resume recording"
            track.isRunning -> "Pause"
            else -> "Resume"
        }
        stop.isEnabled = finished == null && track != null && !track.isStopped
        share.isEnabled = finished?.download != null

        details.text = buildString {
            if (track != null) {
                append("Fixes: ${track.fixes} (dropped ${track.dropped})\n")
                append("Longest wait for GPS: ${track.longestGapMillis / 1000} s\n")
            }
            append("Battery optimization: ")
            append(if (ignoringBatteryOptimizations()) "off for Calcpace (good)" else "ON — the phone may stop the recorder")
        }
    }

    private fun shareLast() {
        val uri = RecorderService.snapshots.value?.finished?.download ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share the run"))
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun permissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun ignoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
}
