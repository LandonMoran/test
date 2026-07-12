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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Foreground service that exercises Shizuku's UserService bind/unbind path
 * looking for issue #201 ("UserService cannot reconnect after unbind").
 *
 * Unlike the original version, the pass/fail oracle here is a DIRECT connection
 * measurement, not a logcat grep: a real ServiceConnection + CountDownLatch that
 * waits for onServiceConnected with a live binder. A bind that never connects is
 * exactly the reported symptom, and needs no READ_LOGS. Three things run:
 *
 *  - rapid churn cycles (randomised 0-220ms pre-unbind delay) keep creating the
 *    token-race window where an unbind evicts a still-in-flight spawn;
 *  - a health probe every [PROBE_INTERVAL] churns does a verified bind that must
 *    connect, retrying a few times - reconnecting on a retry is RECOVERED (the
 *    fix working), never reconnecting is a HARD_FAIL (the bug);
 *  - an idle-reconnect probe every [IDLE_PROBE_INTERVAL] churns waits
 *    [IDLE_GAP_MS] (long enough for the manager to be frozen) and then verifies
 *    a reconnect - the reporter's exact "disconnect, wait, reconnect" case.
 *
 * A reclassified logcat scan is kept only as a secondary signal, and explicitly
 * does NOT treat the fix's graceful lines as failures.
 *
 * Counts (clean / recovered / hard-fail) are appended to a shareable result
 * file so a run is a persistent, checkable record rather than a bare hit/no-hit.
 */
class StressTestService : Service() {

    @Volatile
    private var running = false

    private val logTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val rnd = Random()

    private enum class Probe { CONNECTED, RECOVERED, HARD_FAIL, LOST }

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

        val mode = intent?.getStringExtra(EXTRA_MODE)

        createChannel()
        startForeground(NOTIF_ID, buildProgressNotification("Starting…"))

        if (mode == MODE_DROPREBIND) {
            Thread { runDropRebindLoop() }.start()
        } else {
            Thread { runLoop() }.start()
        }

        return START_NOT_STICKY
    }

    /**
     * Drives the drop-vs-rebind finding: a spawn that fails while a rebind is
     * already waiting on the same record. Each iteration:
     *   1. bind A (schedules the server spawn, which the harness forces to
     *      fail-once via SHIZUKU_REPRO_FORCE_SPAWN_FAIL);
     *   2. brief delay so the forced spawn is still in flight;
     *   3. unbind(remove=true) A -> the record is marked pendingDestroy but the
     *      in-flight spawn is untouched;
     *   4. immediately bind B (verifiedBind) - it REUSES the same still-starting
     *      record and registers as a waiter, then the forced spawn fails.
     * On the BASELINE server dropRecordIfNotAttachedLocked evicts the record and B
     * is stranded (never connects) -> HARD FAILURE. On the FIXED server the failed
     * record still has B waiting, so it respawns and B connects -> clean/RECOVERED.
     */
    private fun runDropRebindLoop() {
        appendResultLog("=== Stress test started (drop-rebind mode) ===")
        val args = argsFor("droprebind")

        var churn = 0
        var connected = 0
        var recovered = 0

        while (running) {
            if (!isShizukuAlive()) {
                halt(churn, "churn=$churn connected=$connected", "Shizuku connection went away")
                return
            }

            churn++

            // 1-3: bind A, let the (forced-failing) spawn get in flight, then
            // unbind(remove=true) while it is still starting.
            try {
                val connA = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {}
                    override fun onServiceDisconnected(name: ComponentName) {}
                }
                Shizuku.bindUserService(args, connA)
                Thread.sleep(DROPREBIND_INFLIGHT_MS)
                Shizuku.unbindUserService(args, connA, true)
            } catch (e: Throwable) {
                appendResultLog("droprebind A bind/unbind threw: ${e.javaClass.simpleName}: ${e.message}")
            }

            // 4: immediately rebind B and require it to connect. This is the record
            // that gets stranded on the baseline. verifiedProbe retries a few times
            // (the fix's respawn may take a moment); never connecting is the bug.
            when (verifiedProbe(args, "droprebind")) {
                Probe.CONNECTED -> connected++
                Probe.RECOVERED -> { connected++; recovered++ }
                Probe.HARD_FAIL -> {
                    reportHardFail(churn,
                        "rebind after unbind(remove=true) of an in-flight failed spawn never connected " +
                            "($RECOVERY_RETRIES retries) - record was dropped with a waiter attached",
                        "churn=$churn connected=$connected ($recovered needed retry)")
                    return
                }
                Probe.LOST -> {
                    halt(churn, "churn=$churn connected=$connected", "Shizuku connection lost")
                    return
                }
            }

            if (churn % PROGRESS_INTERVAL == 0) {
                val s = "churn=$churn connected=$connected ($recovered needed retry)"
                broadcastProgress(churn, s)
                updateProgressNotification(s)
                appendResultLog(s)
            }
        }

        appendResultLog("=== Stopped (manual). churn=$churn connected=$connected ===")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun runLoop() {
        clearLogBuffer()
        var lastLogCheckTime = logTimeFormat.format(Date())

        appendResultLog("=== Stress test started ===")

        val args = argsFor("stress")

        var churn = 0
        var healthProbes = 0
        var healthOk = 0
        var recovered = 0
        var idleProbes = 0
        var idleOk = 0
        var consecutiveThrows = 0

        while (running) {
            if (!isShizukuAlive()) {
                halt(churn, summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk), "Shizuku connection went away")
                return
            }

            if (churn > 0 && churn % ITERATIONS_PER_LOG_CLEAR == 0) {
                clearLogBuffer()
                lastLogCheckTime = logTimeFormat.format(Date())
            }

            // --- rapid churn: create the token-race window ---
            churn++
            try {
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {}
                    override fun onServiceDisconnected(name: ComponentName) {}
                }
                Shizuku.bindUserService(args, conn)
                // Randomised sub-connect delay: sometimes shorter than the spawn
                // takes (so the unbind evicts a still-in-flight record - the #201
                // race), sometimes long enough to connect normally.
                Thread.sleep(rnd.nextInt(CHURN_MAX_DELAY_MS).toLong())
                Shizuku.unbindUserService(args, conn, true)
                consecutiveThrows = 0
            } catch (e: Throwable) {
                consecutiveThrows++
                appendResultLog("churn bind/unbind threw: ${e.javaClass.simpleName}: ${e.message}")
                if (consecutiveThrows >= MAX_CONSECUTIVE_FAILURES) {
                    halt(churn, summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk),
                        "$MAX_CONSECUTIVE_FAILURES bind/unbind calls in a row threw - last: ${e.message}")
                    return
                }
            }

            // --- health probe: does a real connection still complete? ---
            if (churn % PROBE_INTERVAL == 0) {
                healthProbes++
                when (verifiedProbe(args, "health")) {
                    Probe.CONNECTED -> healthOk++
                    Probe.RECOVERED -> { healthOk++; recovered++ }
                    Probe.HARD_FAIL -> {
                        reportHardFail(churn,
                            "health probe never reconnected after $RECOVERY_RETRIES retries",
                            summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk))
                        return
                    }
                    Probe.LOST -> {
                        halt(churn, summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk),
                            "Shizuku connection lost during health probe")
                        return
                    }
                }
            }

            // --- idle-reconnect probe: the reporter's exact scenario ---
            if (churn % IDLE_PROBE_INTERVAL == 0) {
                idleProbes++
                appendResultLog("idle-reconnect probe: waiting ${IDLE_GAP_MS}ms before reconnecting…")
                if (!sleepInterruptible(IDLE_GAP_MS)) break
                when (verifiedProbe(args, "idle")) {
                    Probe.CONNECTED -> idleOk++
                    Probe.RECOVERED -> { idleOk++; recovered++ }
                    Probe.HARD_FAIL -> {
                        reportHardFail(churn,
                            "reconnect after ${IDLE_GAP_MS}ms idle failed after $RECOVERY_RETRIES retries",
                            summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk))
                        return
                    }
                    Probe.LOST -> {
                        halt(churn, summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk),
                            "Shizuku connection lost during idle probe")
                        return
                    }
                }
            }

            // --- secondary: reclassified log scan (best effort, not the oracle) ---
            val checkStartTime = Date()
            val hardSig = scanForHardFail(lastLogCheckTime)
            lastLogCheckTime = logTimeFormat.format(checkStartTime)
            if (hardSig != null) {
                reportHardFail(churn, "hard-failure log signature: $hardSig",
                    summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk))
                return
            }

            if (churn % PROGRESS_INTERVAL == 0) {
                val s = summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk)
                broadcastProgress(churn, s)
                updateProgressNotification(s)
                appendResultLog(s)
            }
        }

        val s = summary(churn, healthProbes, healthOk, recovered, idleProbes, idleOk)
        appendResultLog("=== Stopped (manual). $s ===")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * A verified bind that must connect. If it doesn't within the timeout, retry
     * a few times (without restarting Shizuku) - reconnecting on a retry is the
     * fix's intended self-recovery; never reconnecting is the #201 hard failure.
     */
    private fun verifiedProbe(args: Shizuku.UserServiceArgs, label: String): Probe {
        if (verifiedBind(args, CONNECT_TIMEOUT_MS)) return Probe.CONNECTED

        appendResultLog("$label probe: bind did not connect within ${CONNECT_TIMEOUT_MS}ms; attempting recovery")
        for (i in 1..RECOVERY_RETRIES) {
            if (!running) return Probe.LOST
            if (!sleepInterruptible(RECOVERY_WAIT_MS)) return Probe.LOST
            if (!isShizukuAlive()) return Probe.LOST
            if (verifiedBind(args, CONNECT_TIMEOUT_MS)) {
                appendResultLog("$label probe: reconnected on retry $i (no Shizuku restart needed)")
                return Probe.RECOVERED
            }
        }
        return Probe.HARD_FAIL
    }

    /**
     * Binds the throwaway UserService and waits for a real, pingable connection.
     * Returns true only if onServiceConnected fired within [timeoutMs] with a
     * live binder. Always unbinds (remove=true) before returning.
     */
    private fun verifiedBind(args: Shizuku.UserServiceArgs, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<IBinder>(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                holder[0] = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }

        val connected = try {
            Shizuku.bindUserService(args, conn)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            appendResultLog("verified bind threw: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

        val alive = connected && try {
            holder[0]?.pingBinder() == true
        } catch (e: Throwable) {
            false
        }

        try {
            Shizuku.unbindUserService(args, conn, true)
        } catch (e: Throwable) {
            // best effort - the record is torn down server-side regardless
        }

        return alive
    }

    private fun argsFor(tag: String): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(packageName, StressService::class.java.name))
            .daemon(false)
            .processNameSuffix(tag)
            .tag(tag)

    private fun summary(
        churn: Int,
        healthProbes: Int,
        healthOk: Int,
        recovered: Int,
        idleProbes: Int,
        idleOk: Int
    ): String {
        val healthFail = healthProbes - healthOk
        val idleFail = idleProbes - idleOk
        return "churn=$churn | health $healthOk/$healthProbes ok " +
            "($recovered needed retry, $healthFail hard-fail) | " +
            "idle-reconnect $idleOk/$idleProbes ok ($idleFail fail)"
    }

    /** Cheap local check - not a round trip, just pings the already-held binder. */
    private fun isShizukuAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /** Interruptible sleep that bails out promptly if the test is stopped. */
    private fun sleepInterruptible(totalMs: Long): Boolean {
        var slept = 0L
        while (slept < totalMs) {
            if (!running) return false
            val chunk = minOf(200L, totalMs - slept)
            try {
                Thread.sleep(chunk)
            } catch (e: InterruptedException) {
                return false
            }
            slept += chunk
        }
        return running
    }

    private fun reportHardFail(churn: Int, detail: String, summary: String) {
        running = false
        val msg = "HARD FAILURE at churn=$churn: $detail\n$summary"
        appendResultLog(msg)
        broadcastResult(churn, msg)
        showResultNotification(churn, detail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun halt(churn: Int, summary: String, reason: String) {
        running = false
        appendResultLog("HALTED: $reason | $summary")
        broadcastHalted(churn, reason)
        showHaltedNotification(churn, reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearLogBuffer() {
        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        } catch (e: Exception) {
            // non-fatal; the signature check below just has more history to scan
        }
    }

    /**
     * Secondary signal only. Genuine failures - NOT the fix's graceful lines
     * ("... retrying", "retry works", "destroying the attaching binder instead"),
     * which are the fix working and must never be flagged as the bug.
     */
    private fun scanForHardFail(sinceTime: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-T", sinceTime))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var match: String? = null
            reader.forEachLine { line ->
                if (match == null && isHardFailLine(line)) {
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

    private fun isHardFailLine(line: String): Boolean {
        // Never a failure: the fix's graceful/recovery lines.
        if (line.contains("retrying") ||
            line.contains("retry works") ||
            line.contains("destroying the attaching binder instead")
        ) {
            return false
        }
        return line.contains("unable to find token") ||
            (line.contains("provider is null") && line.contains("gave up after")) ||
            line.contains("System.exit called, status: 1")
    }

    /** Plain-file, independently-checkable record - not just an in-memory counter. */
    private fun appendResultLog(line: String) {
        try {
            FileOutputStream(File(filesDir, RESULT_LOG_FILENAME), true).use {
                it.write("${fileTimeFormat.format(Date())}  $line\n".toByteArray())
            }
        } catch (e: Exception) {
            // best effort - losing a log line isn't worth crashing the test over
        }
    }

    private fun broadcastProgress(churn: Int, summary: String) {
        val intent = Intent(ACTION_PROGRESS)
            .setPackage(packageName)
            .putExtra(EXTRA_ITERATION, churn)
            .putExtra(EXTRA_MATCH, summary)
        sendBroadcast(intent)
    }

    private fun broadcastResult(churn: Int, message: String) {
        val intent = Intent(ACTION_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_ITERATION, churn)
            .putExtra(EXTRA_MATCH, message)
        sendBroadcast(intent)
    }

    private fun broadcastHalted(churn: Int, reason: String) {
        val intent = Intent(ACTION_HALTED)
            .setPackage(packageName)
            .putExtra(EXTRA_ITERATION, churn)
            .putExtra(EXTRA_REASON, reason)
        sendBroadcast(intent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Stress test", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stress test running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateProgressNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildProgressNotification(text))
    }

    private fun showResultNotification(churn: Int, detail: String) {
        val text = "$detail (at churn $churn)"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("#201 hard failure reproduced")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, notification)
    }

    private fun showHaltedNotification(churn: Int, reason: String) {
        val text = "$reason after $churn churn cycles"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stress test stopped")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
        const val EXTRA_MODE = "mode"
        const val MODE_DROPREBIND = "droprebind"

        const val RESULT_LOG_FILENAME = "stress_results.log"

        private const val CHANNEL_ID = "stress_test"
        private const val NOTIF_ID = 42

        private const val CONNECT_TIMEOUT_MS = 5000L
        private const val CHURN_MAX_DELAY_MS = 220
        private const val RECOVERY_RETRIES = 3
        private const val RECOVERY_WAIT_MS = 1500L
        private const val PROBE_INTERVAL = 25
        private const val IDLE_PROBE_INTERVAL = 200
        private const val IDLE_GAP_MS = 8000L
        private const val PROGRESS_INTERVAL = 25
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val ITERATIONS_PER_LOG_CLEAR = 2000
        // Drop-rebind: how long to let the (forced-failing) spawn stay in flight
        // between bind A and unbind(remove=true) A, so the rebind B lands while the
        // record is still "starting" and takes the reuse path.
        private const val DROPREBIND_INFLIGHT_MS = 300L
    }
}
