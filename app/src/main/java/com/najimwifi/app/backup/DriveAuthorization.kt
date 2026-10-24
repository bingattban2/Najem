package com.najimwifi.app.backup

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only the hidden, app-owned Drive folder is requested. No tokens are stored. */
class DriveAuthorization(context: Context) {
    companion object {
        const val APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }

    private val client = Identity.getAuthorizationClient(context.applicationContext)
    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(APPDATA_SCOPE)))
        .build()

    fun authorize(
        resolve: (IntentSenderRequest) -> Unit,
        success: (String) -> Unit,
        failure: (String) -> Unit
    ) {
        client.authorize(request())
            .addOnSuccessListener { result ->
                try {
                    if (result.hasResolution()) {
                        val pending = requireNotNull(result.pendingIntent)
                        resolve(IntentSenderRequest.Builder(pending.intentSender).build())
                    } else {
                        success(token(result))
                    }
                } catch (e: Exception) { failure(e.message ?: "تعذر طلب صلاحية Drive") }
            }
            .addOnFailureListener { failure(it.message ?: "تعذر ربط حساب Google") }
    }

    fun finishAuthorization(data: Intent?): String {
        val result = client.getAuthorizationResultFromIntent(
            requireNotNull(data) { "لم يكتمل تفويض Google Drive" }
        )
        return token(result)
    }

    /** Background workers may reuse an existing grant but never display consent UI. */
    suspend fun tokenWithoutUi(): String = suspendCancellableCoroutine { continuation ->
        client.authorize(request())
            .addOnSuccessListener { result ->
                if (!continuation.isActive) return@addOnSuccessListener
                try {
                    require(!result.hasResolution()) {
                        "AUTH_REQUIRED: افتح الإعدادات ووافق على صلاحية Google Drive"
                    }
                    continuation.resume(token(result))
                } catch (e: Exception) { continuation.resumeWithException(e) }
            }
            .addOnFailureListener {
                if (continuation.isActive) continuation.resumeWithException(it)
            }
    }

    private fun token(result: AuthorizationResult): String {
        require(APPDATA_SCOPE in result.grantedScopes.orEmpty()) {
            "لم يمنح المستخدم صلاحية مجلد النسخ الخاص بالتطبيق"
        }
        return result.accessToken?.takeIf { it.isNotBlank() }
            ?: error("لم تُصدر Google رمز وصول صالحًا")
    }
}
