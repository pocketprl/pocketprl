package dev.pocketprl.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object Biometrics {
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    sealed class Outcome {
        class Success(val cipher: Cipher) : Outcome()
        class Cancelled : Outcome()
        class Error(val message: String) : Outcome()
    }

    /** Shows the system prompt bound to a Keystore cipher. Resolves once the user is done. */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String, cipher: Cipher, negative: String = "Use password"): Outcome =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (cont.isActive) cont.resume(if (c != null) Outcome.Success(c) else Outcome.Error("No crypto object"))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    cont.resume(if (cancelled) Outcome.Cancelled() else Outcome.Error(errString.toString()))
                }

                override fun onAuthenticationFailed() { /* try again; prompt stays open */ }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negative)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
}
