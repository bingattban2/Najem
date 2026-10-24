package com.najimwifi.app.backup

/** Destructive Drive cleanup is opt-in, independent of scheduling. */
internal object BackupRetentionPolicy {
    fun mayDeleteOldVersions(jobEnabled: Boolean, ownerConsent: Boolean): Boolean =
        jobEnabled && ownerConsent
}
