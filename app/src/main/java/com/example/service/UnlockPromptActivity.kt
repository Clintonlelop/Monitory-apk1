package com.example.service

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import com.example.DeviceManagerApp

/**
 * Transparent activity that performs the only unlock behavior Android permits
 * for a normal app: turn the display on and *request* keyguard dismissal.
 *
 * - Devices with no secure screen lock (swipe/none) are dismissed outright.
 * - Devices with a secure lock (PIN/pattern/password/fingerprint) show the
 *   system authentication prompt; the agent CANNOT bypass it, and the result
 *   is reported honestly (never "unlocked" just because a wake lock fired).
 *
 * Launched by the UNLOCK_DEVICE command handler in DeviceRepository; the final
 * command status is reported from the KeyguardDismissCallback.
 */
class UnlockPromptActivity : Activity() {

    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID)

        // Keep the screen on while the prompt is visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }

        if (commandId == null) {
            finish()
            return
        }

        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null) {
            reportFailure(commandId, "KeyguardManager is unavailable on this device — display wake attempted only.")
            finish()
            return
        }

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            // Pre-8.1 fallback: full wake lock with ACQUIRE_CAUSES_WAKEUP.
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            val wakeLock = pm?.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "Monitory:UnlockWakeLock"
            )
            wakeLock?.acquire(3_000L)
        }

        try {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissError() {
                    reportFailure(
                        commandId,
                        "Display wake signal sent, but the keyguard could not be dismissed. " +
                            "If a secure screen lock (PIN/pattern/password) is set, Android requires the device user to authenticate — " +
                            "the agent cannot bypass an authenticated lock screen."
                    )
                    finish()
                }

                override fun onDismissSucceeded() {
                    reportSuccess(
                        commandId,
                        "Display is on and the keyguard was dismissed. (This succeeds without user input only when the device has no secure screen lock; " +
                            "with a secure lock the device user must have authenticated.)"
                    )
                    finish()
                }

                override fun onDismissCancelled() {
                    reportFailure(
                        commandId,
                        "Keyguard dismissal was cancelled — the device user did not authenticate. The screen lock remains in place."
                    )
                    finish()
                }
            })
        } catch (e: Exception) {
            reportFailure(commandId, "Could not request keyguard dismissal: ${e.message}. Display wake signal sent only.")
            finish()
        }
    }

    private fun reportSuccess(commandId: String, message: String) {
        if (reported) return
        reported = true
        DeviceManagerApp.instance.repository.reportCommandSuccessAsync(commandId, message)
    }

    private fun reportFailure(commandId: String, message: String) {
        if (reported) return
        reported = true
        DeviceManagerApp.instance.repository.reportCommandFailureAsync(commandId, message)
    }

    companion object {
        const val EXTRA_COMMAND_ID = "command_id"
    }
}
