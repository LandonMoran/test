package com.landonmoran.repro201tester

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button

    private var testRunning = false
    private var grantInProgress = false
    private var batteryPromptDismissed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var grantTimeoutRunnable: Runnable? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            refresh()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refresh() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refresh() }
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                StressTestService.ACTION_PROGRESS -> {
                    val n = intent.getIntExtra(StressTestService.EXTRA_ITERATION, 0)
                    statusText.text = "Running… iteration $n"
                }
                StressTestService.ACTION_RESULT -> {
                    val n = intent.getIntExtra(StressTestService.EXTRA_ITERATION, 0)
                    val line = intent.getStringExtra(StressTestService.EXTRA_MATCH)
                    testRunning = false
                    statusText.text = "Hit after $n iterations:\n$line"
                    actionButton.visibility = View.VISIBLE
                    actionButton.text = "Run again"
                    actionButton.setOnClickListener { startStressTest() }
                }
                StressTestService.ACTION_HALTED -> {
                    val n = intent.getIntExtra(StressTestService.EXTRA_ITERATION, 0)
                    val reason = intent.getStringExtra(StressTestService.EXTRA_REASON)
                    testRunning = false
                    statusText.text = "Stopped: connection lost after $n confirmed iterations.\n$reason"
                    actionButton.visibility = View.VISIBLE
                    actionButton.text = "Retry"
                    actionButton.setOnClickListener { refresh() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        val filter = IntentFilter().apply {
            addAction(StressTestService.ACTION_PROGRESS)
            addAction(StressTestService.ACTION_RESULT)
            addAction(StressTestService.ACTION_HALTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(progressReceiver, filter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Shizuku, the battery-optimization settings screen,
        // or just switching apps - the live state may have changed underneath us.
        refresh()
    }

    override fun onDestroy() {
        grantTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        unregisterReceiver(progressReceiver)
        super.onDestroy()
    }

    /** True only if Shizuku's binder is alive right now - never trust a cached result. */
    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    private fun hasShizukuPermission(): Boolean {
        return try {
            isShizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refresh() {
        if (testRunning || grantInProgress) {
            return
        }

        if (!isShizukuReady()) {
            statusText.text = "Shizuku isn't running. Start Shizuku, then tap Retry."
            actionButton.visibility = View.VISIBLE
            actionButton.text = "Retry"
            actionButton.setOnClickListener { refresh() }
            return
        }

        if (!hasShizukuPermission()) {
            statusText.text = "This app needs Shizuku permission to run the stress test."
            actionButton.visibility = View.VISIBLE
            actionButton.text = "Grant Shizuku permission"
            actionButton.setOnClickListener { requestShizukuPermission() }
            return
        }

        val hasReadLogs = ContextCompat.checkSelfPermission(this, "android.permission.READ_LOGS") ==
            PackageManager.PERMISSION_GRANTED
        if (!hasReadLogs) {
            statusText.text = "Granting this app log-read access via Shizuku…"
            actionButton.visibility = View.GONE
            grantReadLogsThenClose()
            return
        }

        // Best-effort: the foreground service already keeps the loop itself
        // running, but exempting from Doze/App Standby avoids the process
        // being frozen or throttled between cycles on aggressive OEM ROMs.
        if (!batteryPromptDismissed && !isIgnoringBatteryOptimizations()) {
            statusText.text = "For best results, exempt this app from battery optimization " +
                "(some ROMs throttle background work even during a foreground service)."
            actionButton.visibility = View.VISIBLE
            actionButton.text = "Exempt from battery optimization"
            actionButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
            return
        }

        statusText.text = "Ready."
        actionButton.visibility = View.VISIBLE
        actionButton.text = "Start stress test"
        actionButton.setOnClickListener { startStressTest() }
    }

    private fun requestShizukuPermission() {
        // Re-check right before acting - the UI state could be stale by the
        // time the user actually taps the button.
        if (!isShizukuReady()) {
            Toast.makeText(this, "Shizuku isn't running anymore. Start it and tap Retry.", Toast.LENGTH_LONG).show()
            refresh()
            return
        }
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            Toast.makeText(this, "Couldn't request permission: ${e.message}", Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun grantReadLogsThenClose() {
        if (grantInProgress) {
            return
        }

        // Re-check right before starting - don't attempt this against a
        // binder/permission state that might already be gone.
        if (!hasShizukuPermission()) {
            grantInProgress = false
            refresh()
            return
        }

        grantInProgress = true

        val args = Shizuku.UserServiceArgs(ComponentName(packageName, SetupService::class.java.name))
            .daemon(false)
            .processNameSuffix("setup")
            .tag("setup")

        var finished = false

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (finished) return
                finished = true
                grantTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                try {
                    Shizuku.unbindUserService(args, this, true)
                } catch (e: Throwable) {
                    // best effort - the record is torn down server-side regardless
                }
                grantInProgress = false

                // The UserService's `pm grant` runs in a separate process and
                // swallows its own errors, so the only trustworthy signal is
                // checking the permission from here. Don't assume success and
                // close - that's what turns a real failure into an infinite
                // "granted! close and reopen" loop.
                val actuallyGranted = ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.READ_LOGS") ==
                    PackageManager.PERMISSION_GRANTED
                if (actuallyGranted) {
                    statusText.text = "Permission granted. Close and reopen the app."
                    mainHandler.postDelayed({ finishAndRemoveTask() }, 1500)
                } else {
                    statusText.text = "Grant didn't take. Make sure Shizuku is running as root/adb shell, then retry."
                    actionButton.visibility = View.VISIBLE
                    actionButton.text = "Retry"
                    actionButton.setOnClickListener { refresh() }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }

        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            grantInProgress = false
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (e: Throwable) {
                // best effort
            }
            statusText.text = "Couldn't grant log-read access (timed out). Make sure Shizuku is running, then retry."
            actionButton.visibility = View.VISIBLE
            actionButton.text = "Retry"
            actionButton.setOnClickListener { refresh() }
        }
        grantTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, GRANT_TIMEOUT_MS)

        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            mainHandler.removeCallbacks(timeout)
            grantInProgress = false
            statusText.text = "Couldn't start the permission grant: ${e.message}"
            actionButton.visibility = View.VISIBLE
            actionButton.text = "Retry"
            actionButton.setOnClickListener { refresh() }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        // Only ask once per session - if the user declines the system dialog,
        // don't trap them behind this screen forever.
        batteryPromptDismissed = true
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: Throwable) {
            // Not all OEM skins expose this dialog - don't block the user on it.
            Toast.makeText(this, "Couldn't open battery settings; continuing without the exemption.", Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun startStressTest() {
        if (!hasShizukuPermission()) {
            Toast.makeText(this, "Shizuku isn't ready anymore.", Toast.LENGTH_LONG).show()
            refresh()
            return
        }

        testRunning = true
        statusText.text = "Starting…"
        actionButton.visibility = View.VISIBLE
        actionButton.text = "Stop"
        actionButton.setOnClickListener { stopStressTest() }

        val intent = Intent(this, StressTestService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStressTest() {
        testRunning = false
        val intent = Intent(this, StressTestService::class.java).setAction(StressTestService.ACTION_STOP)
        startService(intent)
        statusText.text = "Stopped."
        actionButton.text = "Start stress test"
        actionButton.setOnClickListener { startStressTest() }
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val GRANT_TIMEOUT_MS = 6000L
    }
}
