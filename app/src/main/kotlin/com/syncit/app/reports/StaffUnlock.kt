package com.syncit.app.reports

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * "Use my authentication" (user's request, 2026-11-10): App reports opens only after the phone's own
 * fingerprint, face or screen lock. The same unlock is what lets the Keystore key decrypt reports
 * (ReportCrypto), so this isn't just a screen in front of the data.
 *
 * Returns a function that shows the prompt; [onResult] gets true once unlocked.
 */
@Composable
fun rememberStaffUnlock(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onResult)
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        callback.value(result.resultCode == Activity.RESULT_OK)
    }
    return remember(context) {
        {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            when {
                keyguard?.isDeviceSecure != true -> callback.value(false)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> showPrompt(context) { callback.value(it) }
                else -> {
                    @Suppress("DEPRECATION")
                    val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock App reports", "Use your screen lock to continue")
                    if (intent == null) callback.value(false) else legacy.launch(intent)
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun showPrompt(context: Context, onResult: (Boolean) -> Unit) {
    val activity = context.findActivity() ?: return onResult(false)
    val builder = BiometricPrompt.Builder(activity)
        .setTitle("Unlock App reports")
        .setSubtitle("Use your fingerprint, face or screen lock")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    builder.build().authenticate(
        CancellationSignal(),
        activity.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
