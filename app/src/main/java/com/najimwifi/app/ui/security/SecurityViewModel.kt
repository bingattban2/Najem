package com.najimwifi.app.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najimwifi.app.security.LockPolicy
import com.najimwifi.app.security.LoginSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SecurityUiState(
    val configured: Boolean = false,
    val locked: Boolean = true,
    val username: String = "",
    val policy: LockPolicy = LockPolicy.OFF,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class SecurityViewModel(private val manager: LoginSessionManager) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<SecurityUiState> = combine(
        manager.state, busy, error, message
    ) { session, isBusy, issue, notice ->
        SecurityUiState(
            configured = session.configured,
            locked = session.locked,
            username = session.username,
            policy = session.policy,
            busy = isBusy,
            error = session.storageError ?: issue,
            message = notice
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SecurityUiState(
            configured = manager.state.value.configured,
            locked = manager.state.value.locked,
            username = manager.state.value.username,
            policy = manager.state.value.policy,
            error = manager.state.value.storageError
        )
    )

    fun onBackground() = manager.onBackground()
    fun onForeground() = manager.onForeground()
    fun lockNow() = manager.lockNow()

    fun clearMessages() {
        if (!busy.value) {
            error.value = null
            message.value = null
        }
    }

    private fun execute(
        successMessage: String,
        work: suspend () -> Unit
    ) {
        if (busy.value) return
        busy.value = true
        clearInternalMessages()
        viewModelScope.launch {
            try {
                work()
                message.value = successMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failure.message ?: "تعذر تنفيذ إجراء الحماية"
            } finally {
                busy.value = false
            }
        }
    }

    private fun clearInternalMessages() {
        error.value = null
        message.value = null
    }

    fun unlock(username: String, password: String) =
        execute("تم فتح القفل") {
            manager.unlock(username, password)
        }

    fun activate(username: String, password: String, confirm: String, policy: LockPolicy) =
        execute("تم تفعيل حماية الدخول") {
            require(password == confirm) { "تأكيد كلمة المرور غير مطابق" }
            manager.activate(username, password, policy)
        }

    fun updateCredentials(
        oldPassword: String,
        username: String,
        password: String,
        confirm: String
    ) = execute("تم تحديث بيانات الدخول") {
        require(password == confirm) { "تأكيد كلمة المرور غير مطابق" }
        manager.changeCredentials(oldPassword, username, password)
    }

    fun updatePolicy(password: String, policy: LockPolicy) =
        execute(if (policy == LockPolicy.OFF) "تم إيقاف القفل" else "تم تحديث مدة القفل") {
            manager.changePolicy(password, policy)
        }
}