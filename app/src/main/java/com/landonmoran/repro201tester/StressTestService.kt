package com.landonmoran.repro201tester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Foreground service that repeatedly binds/unbinds a throwaway UserService as
 * fast as possible, and after each cycle checks its own logcat (via the
 * READ_LOGS permission granted by SetupService) for the #201 token-race
 * signature. Stops and notifies on a match, on a lost Shizuku connection, or
 * if manually stopped - it never keeps "running" once the underlying calls
 * stop actually reaching Shizuku.
 */
class StressTestService : Service() {

    @Volatile
    private var running = false

    private val noopConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {}
        override fun onServiceDisconnected(name: ComponentName) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            return START_NOT_STICKY
        }

        if (running) {
            return START_NOT_STICKY
        }
        running = true

        createChannel()
        startForeground(NOTIF_ID, buildProgressNotification(0, 0))

        Thread { runLoop() }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun runLoop() {
        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        } catch (e: Exception) {
            // non-fatal; the signature check below just has more history to scan
        }

        val args = Shizuku.UserServiceArgs(ComponentName(packageName, StressService::class.java.name))
            .daemon(false)
            .processNameSuffix("stress")
            .tag("stress")

        var iteration = 0
        var confirmedIteration = 0
        var consecutiveFailures = 0

        while (running) {
            iteration++

            // Verify the connection is actually alive BEFORE spending a cycle
            // on it - a dead binder here means every subsequent bind/unbind
            // is a no-op, and we'd otherwise just spin counting fake
            // iterations forever with no way to tell the difference (exactly
            // the "spam it and get 0=0=0" blind spot the shell-broadcast
            // approach had).
            if (!isShizukuAlive()) {
                haltForLostConnection(confirmedIteration, "Shizuku connection went away")
                return
            }

            var cycleOk = true
            var lastFailure: String? = null
            try {
                Shizuku.bindUserService(args, noopConnection)
                Thread.sleep(150)
                Shizuku.unbindUserService(args, noopConnection, true)
            } catch (e: Throwable) {
                cycleOk = false
                lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            }

            if (cycleOk) {
                confirmedIteration = iteration
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    haltForLostConnection(
                        confirmedIteration,
                        "$MAX_CONSECUTIVE_FAILURES bind/unbind calls in a row failed - last error: $lastFailure"
                    )
                    return
                }
            }

            val hit = checkForSignature()
            if (hit != null) {
                running = false
                broadcastResult(confirmedIteration, hit)
                showResultNotification(confirmedIteration, hit)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            if (iteration % 5 == 0) {
                broadcastProgress(confirmedIteration, iteration)
                updateProgressNotification(confirmedIteration, iteration)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Cheap local check - not a round trip, just pings the already-held binder. */
    private fun isShizukuAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    private fun haltForLostConnection(confirmedIteration: Int, reason: String) {
        running = false
        broadcastHalted(confirmedIteration, reason)
        showHaltedNotification(confirmedIteration, reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun checkForSignature(): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var match: String? = null
            reader.forEachLine { line ->
                if (match == null &&
                    (line.contains("unable to find token") ||
                        line.contains("destroying the attaching binder instead"))
                ) {
                    match = line
                }
            }
            reader.close()
            process.waitFor()
            match
        } catch (e: Exception) {
            null
        }
    }

    private fun broadcastProgress(confirmedIteration: Int, attempted: Int) {
        val intent = Intent(ACTION_PROGRESS)
            .setPackage(packageName)
            .putExtra(EXTRA_ITERATION, confirmedIteration)
            .putExtra(EXTRA_ATTEMPTED, attempted)
        sendBroadcast(intent)
    }

    private fun broadcastResult(iteration: Int, matchedLine: String) {
        val intent = Intent(ACTION_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_ITERATION, iteration)
            .putExtra(EXTRA_MATCH, matchedLine)
        sendBroadcast(intent)
    }

    private fun broadcastHalted(confirmedIteration: Int, reason: String) {
        val intent = Intent(ACTION_HALTED)
            .setPackage(packageName)
            .putExtra(EXTRA_ITERATION, confirmedIteration)
            .putExtra(EXTRA_REASON, reason)
        sendBroadcast(intent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Stress test", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(confirmedIteration: Int, attempted: Int): Notification {
        val text = if (attempted > confirmedIteration) {
            "Confirmed $confirmedIteration (attempted $attempted)"
        } else {
            "Confirmed $confirmedIteration"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stress test running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateProgressNotification(confirmedIteration: Int, attempted: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildProgressNotification(confirmedIteration, attempted))
    }

    private fun showResultNotification(iteration: Int, matchedLine: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Race hit after $iteration confirmed iterations")
            .setContentText(matchedLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(matchedLine))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, notification)
    }

    private fun showHaltedNotification(confirmedIteration: Int, reason: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stress test stopped: connection lost")
            .setContentText("$reason after $confirmedIteration confirmed iterations")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason after $confirmedIteration confirmed iterations"))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 2, notification)
    }

    companion object {
        const val ACTION_STOP = "com.landonmoran.repro201tester.STOP"
        const val ACTION_PROGRESS = "com.landonmoran.repro201tester.PROGRESS"
        const val ACTION_RESULT = "com.landonmoran.repro201tester.RESULT"
        const val ACTION_HALTED = "com.landonmoran.repro201tester.HALTED"
        const val EXTRA_ITERATION = "iteration"
        const val EXTRA_ATTEMPTED = "attempted"
        const val EXTRA_MATCH = "match"
        const val EXTRA_REASON = "reason"

        private const val CHANNEL_ID = "stress_test"
        private const val NOTIF_ID = 42
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
