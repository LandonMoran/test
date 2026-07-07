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
 * signature. Stops and notifies on a match, or if manually stopped.
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
        startForeground(NOTIF_ID, buildProgressNotification(0))

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
        while (running) {
            iteration++
            try {
                Shizuku.bindUserService(args, noopConnection)
                Thread.sleep(150)
                Shizuku.unbindUserService(args, noopConnection, true)
            } catch (e: Throwable) {
                // A bind/unbind hiccup here isn't itself the signal we're
                // hunting for; keep looping and let the log check judge it.
            }

            val hit = checkForSignature()
            if (hit != null) {
                running = false
                broadcastResult(iteration, hit)
                showResultNotification(iteration, hit)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            if (iteration % 5 == 0) {
                broadcastProgress(iteration)
                updateProgressNotification(iteration)
            }
        }

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

    private fun broadcastProgress(iteration: Int) {
        val intent = Intent(ACTION_PROGRESS).setPackage(packageName).putExtra(EXTRA_ITERATION, iteration)
        sendBroadcast(intent)
    }

    private fun broadcastResult(iteration: Int, matchedLine: String) {
        val intent = Intent(ACTION_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_ITERATION, iteration)
            .putExtra(EXTRA_MATCH, matchedLine)
        sendBroadcast(intent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Stress test", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(iteration: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stress test running")
            .setContentText("Iteration $iteration")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateProgressNotification(iteration: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildProgressNotification(iteration))
    }

    private fun showResultNotification(iteration: Int, matchedLine: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Race hit after $iteration iterations")
            .setContentText(matchedLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(matchedLine))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, notification)
    }

    companion object {
        const val ACTION_STOP = "com.landonmoran.repro201tester.STOP"
        const val ACTION_PROGRESS = "com.landonmoran.repro201tester.PROGRESS"
        const val ACTION_RESULT = "com.landonmoran.repro201tester.RESULT"
        const val EXTRA_ITERATION = "iteration"
        const val EXTRA_MATCH = "match"

        private const val CHANNEL_ID = "stress_test"
        private const val NOTIF_ID = 42
    }
}
