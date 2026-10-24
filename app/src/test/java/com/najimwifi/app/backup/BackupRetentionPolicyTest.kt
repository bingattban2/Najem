package com.najimwifi.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRetentionPolicyTest {
    @Test fun noCleanupWithoutExplicitConsent() {
        assertFalse(BackupRetentionPolicy.mayDeleteOldVersions(true, false))
        assertFalse(BackupRetentionPolicy.mayDeleteOldVersions(false, false))
    }

    @Test fun evenPriorConsentDoesNotDeleteForDisabledJob() {
        assertFalse(BackupRetentionPolicy.mayDeleteOldVersions(false, true))
    }

    @Test fun authorizedActiveScheduleCanApplyRetention() {
        assertTrue(BackupRetentionPolicy.mayDeleteOldVersions(true, true))
    }
}
