package com.najimwifi.app.domain.model

enum class LockInterval(val minutes: Int) {
    IMMEDIATELY(0),
    ONE_MINUTE(1),
    FIVE_MINUTES(5),
    FIFTEEN_MINUTES(15),
    THIRTY_MINUTES(30)
}

enum class BackupFrequency { MANUAL, DAILY, WEEKLY }

data class BackupPreferences(
    val frequency: BackupFrequency = BackupFrequency.MANUAL,
    val retainedSuccessfulCopies: Int = 3,
    val selectedGoogleAccount: String? = null,
    val lastSuccessfulBackupAtEpochMillis: Long? = null,
    val lastUploadError: String? = null
) {
    init {
        require(retainedSuccessfulCopies >= 2) {
            "At least two successful backup copies must be retained"
        }
        require(selectedGoogleAccount == null || selectedGoogleAccount.isNotBlank())
        require(
            lastSuccessfulBackupAtEpochMillis == null ||
                lastSuccessfulBackupAtEpochMillis >= 0L
        )
    }
}

data class LocalAlertPreferences(
    val enabled: Boolean = false,
    val lowStockThreshold: Long? = null,
    val debtThresholdMinor: Long? = null,
    val overdueDays: Int? = null
) {
    init {
        require(lowStockThreshold == null || lowStockThreshold >= 0L)
        require(debtThresholdMinor == null || debtThresholdMinor > 0L)
        require(overdueDays == null || overdueDays > 0)
    }
}

/** Public preferences only: no passwords, recovery phrases or encryption keys. */
data class AppConfig(
    val localLockEnabled: Boolean = false,
    val lockInterval: LockInterval = LockInterval.IMMEDIATELY,
    val businessHeader: String? = null,
    val backup: BackupPreferences = BackupPreferences(),
    val alerts: LocalAlertPreferences = LocalAlertPreferences()
) {
    init {
        require(businessHeader == null || businessHeader.isNotBlank())
    }
}