package com.landonmoran.repro201tester

import android.os.Binder

/**
 * Throwaway UserService payload. Runs entirely with the root/shell identity a
 * Shizuku UserService process gets, so its init block can self-grant this
 * app's own package the READ_LOGS permission (via a plain privileged
 * `pm grant`) without needing any hidden-API reflection.
 */
class SetupService : Binder() {
    init {
        try {
            Runtime.getRuntime()
                .exec(arrayOf("pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.READ_LOGS"))
                .waitFor()
        } catch (e: Exception) {
            // Best effort - MainActivity re-checks the permission on next launch
            // and simply retries this step if it didn't take.
        }
    }
}
