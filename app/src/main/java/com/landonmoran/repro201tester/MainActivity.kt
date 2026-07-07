package com.landonmoran.repro201tester

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button

    private var testRunning = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            refresh()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
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

        val filter = IntentFilter().apply {
            addAction(StressTestService.ACTION_PROGRESS)
            addAction(StressTestService.ACTION_RESULT)
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

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        unregisterReceiver(progressReceiver)
        super.onDestroy()
    }

    private fun refresh() {
        if (testRunning) {
            return
        }

        if (!Shizuku.pingBinder()) {
            statusText.text = "Shizuku isn't running. Start Shizuku, then tap Retry."
            actionButton.visibility = View.VISIBLE
            actionButton.text = "Retry"
            actionButton.setOnClickListener { refresh() }
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "This app needs Shizuku permission to run the stress test."
            actionButton.visibility = View.VISIBLE
            actionButton.text = "Grant Shizuku permission"
            actionButton.setOnClickListener { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
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

        statusText.text = "Ready."
        actionButton.visibility = View.VISIBLE
        actionButton.text = "Start stress test"
        actionButton.setOnClickListener { startStressTest() }
    }

    private fun grantReadLogsThenClose() {
        val args = Shizuku.UserServiceArgs(ComponentName(packageName, SetupService::class.java.name))
            .daemon(false)
            .processNameSuffix("setup")
            .tag("setup")

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Shizuku.unbindUserService(args, this, true)
                statusText.text = "Permission granted. Close and reopen the app."
                Handler(Looper.getMainLooper()).postDelayed({ finishAndRemoveTask() }, 1500)
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        Shizuku.bindUserService(args, connection)
    }

    private fun startStressTest() {
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
    }
}
