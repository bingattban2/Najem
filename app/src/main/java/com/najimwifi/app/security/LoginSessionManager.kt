package com.najimwifi.app.security

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fail-closed local lock; it never accesses the Internet. */
data class SessionSnapshot(
    val configured: Boolean,
    val locked: Boolean,
    val username: String = "",
    val policy: LockPolicy = LockPolicy.OFF,
    val storageError: String? = null
)

class LoginSessionManager(context: Context) {
    private val store = CredentialStore(context)
    private val mutex = Mutex()
    private var storageError: String? = null
    private var credential: LocalCredential? = try {
        store.read()
    } catch (error: Exception) {
        storageError = "تعذر قراءة بيانات القفل. لا يمكن فتح التطبيق دون استعادة حماية الجهاز الأصلية."
        null
    }

    private val current = MutableStateFlow(
        SessionSnapshot(
            configured = credential != null || storageError != null,
            locked = storageError != null || credential?.policy?.isEnabled == true,
            username = credential?.username.orEmpty(),
            policy = credential?.policy ?: LockPolicy.OFF,
            storageError = storageError
        )
    )
    val state: StateFlow<SessionSnapshot> = current
    private var leftAtElapsedRealtime: Long? = null

    fun onBackground() {
        val policy = current.value.policy
        if (!current.value.configured || !policy.isEnabled) return
        leftAtElapsedRealtime = SystemClock.elapsedRealtime()
        if (policy == LockPolicy.IMMEDIATE) {
            current.value = current.value.copy(locked = true)
        }
    }

    fun onForeground() {
        val since = leftAtElapsedRealtime ?: return
        leftAtElapsedRealtime = null
        val duration = current.value.policy.timeoutMillis ?: return
        val elapsed = SystemClock.elapsedRealtime() - since
        if (elapsed < 0L || elapsed >= duration) {
            current.value = current.value.copy(locked = true)
        }
    }

    fun lockNow() {
        if (current.value.policy.isEnabled) {
            current.value = current.value.copy(locked = true)
        }
    }

    suspend fun activate(
        username: String,
        password: String,
        policy: LockPolicy
    ) = mutex.withLock {
        withContext(Dispatchers.Default) {
            require(credential == null && storageError == null) {
                "القفل مفعّل مسبقًا أو تعذرت قراءة بياناته"
            }
            require(policy.isEnabled) { "اختر مدة القفل" }
            val name = checkedUsername(username)
            val next = LocalCredential(name, PasswordHasher.create(password), policy)
            store.write(next)
            credential = next
            current.value = SessionSnapshot(true, false, name, policy)
        }
    }

    suspend fun unlock(username: String, password: String) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val record = requiredCredential()
            val verified = checkPassword(record, password, username.trim())
            credential = verified
            leftAtElapsedRealtime = null
            current.value = SessionSnapshot(true, false, verified.username, verified.policy)
        }
    }

    /** Verify the configured local owner and return the stored identity for audit. */
    suspend fun authorizeCorrection(password: String): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            require(!current.value.locked) { "افتح القفل أولًا" }
            val record = requiredCredential()
            require(record.policy.isEnabled) {
                "يجب تفعيل القفل المحلي قبل التصحيح الموثق"
            }
            val verified = checkPassword(record, password)
            credential = verified
            verified.username
        }
    }

    suspend fun changePolicy(password: String, policy: LockPolicy) = mutex.withLock {
        withContext(Dispatchers.Default) {
            require(!current.value.locked) { "افتح القفل أولًا" }
            val verified = checkPassword(requiredCredential(), password)
            val next = verified.copy(policy = policy)
            store.write(next)
            credential = next
            current.value = SessionSnapshot(true, false, next.username, policy)
        }
    }

    suspend fun changeCredentials(
        oldPassword: String,
        username: String,
        newPassword: String
    ) = mutex.withLock {
        withContext(Dispatchers.Default) {
            require(!current.value.locked) { "افتح القفل أولًا" }
            val verified = checkPassword(requiredCredential(), oldPassword)
            val next = verified.copy(
                username = checkedUsername(username),
                password = PasswordHasher.create(newPassword)
            )
            store.write(next)
            credential = next
            current.value = SessionSnapshot(true, false, next.username, next.policy)
        }
    }

    private fun checkedUsername(value: String): String = value.trim().also {
        require(it.length in 3..64 && it.none(Char::isISOControl)) {
            "اسم المستخدم يجب أن يكون من 3 إلى 64 حرفًا"
        }
    }

    private fun requiredCredential(): LocalCredential =
        credential ?: error(storageError ?: "لم يتم إعداد القفل")

    private fun checkPassword(
        currentRecord: LocalCredential,
        password: String,
        username: String? = null
    ): LocalCredential {
        val now = System.currentTimeMillis()
        if (now < currentRecord.blockedUntilEpochMillis) {
            val seconds = (currentRecord.blockedUntilEpochMillis - now + 999L) / 1000L
            throw IllegalArgumentException("محاولات كثيرة. أعد المحاولة بعد $seconds ثانية")
        }
        // Always run the KDF even when the supplied username is incorrect.
        val passwordMatches = PasswordHasher.matches(password, currentRecord.password)
        if (!passwordMatches || (username != null && username != currentRecord.username)) {
            val failures = (currentRecord.failures + 1).coerceAtMost(100)
            val step = (failures - 5).coerceAtLeast(0).coerceAtMost(5)
            val wait = if (failures >= 5) (30_000L shl step).coerceAtMost(900_000L) else 0L
            val blockedUntil = if (wait > 0L) Math.addExact(now, wait) else 0L
            val failed = currentRecord.copy(
                failures = failures,
                blockedUntilEpochMillis = blockedUntil
            )
            store.write(failed)
            credential = failed
            throw IllegalArgumentException(
                if (wait > 0L) "محاولات كثيرة. أعد المحاولة بعد ${wait / 1000L} ثانية"
                else "اسم المستخدم أو كلمة المرور غير صحيحين"
            )
        }
        return currentRecord.copy(failures = 0, blockedUntilEpochMillis = 0L).also {
            store.write(it)
        }
    }
}