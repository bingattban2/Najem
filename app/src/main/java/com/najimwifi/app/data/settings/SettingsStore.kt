package com.najimwifi.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.najimwifi.app.domain.service.DebtAlertOptions
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/** Non-secret UI preferences only. Authentication remains in CredentialStore. */
enum class AppearanceMode { SYSTEM, LIGHT, DARK }

data class NajimSettings(
    val businessHeader: String = "",
    val appearance: AppearanceMode = AppearanceMode.SYSTEM,
    val onboardingFinished: Boolean = false,
    val alerts: DebtAlertOptions = DebtAlertOptions()
)

private val Context.najimSettingsDataStore by preferencesDataStore(name = "najim_settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.najimSettingsDataStore

    private object Keys {
        val HEADER = stringPreferencesKey("business_header_v1")
        val APPEARANCE = stringPreferencesKey("appearance_mode_v1")
        val ONBOARDING = booleanPreferencesKey("onboarding_finished_v1")
        val ALERT_DEBT = booleanPreferencesKey("alert_debt_enabled_v1")
        val ALERT_MINIMUM = longPreferencesKey("alert_minimum_debt_minor_v1")
        val ALERT_DELAY = booleanPreferencesKey("alert_settlement_delay_enabled_v1")
        val ALERT_DAYS = intPreferencesKey("alert_overdue_days_v1")
        val ALERT_SYSTEM = booleanPreferencesKey("alert_system_notification_enabled_v1")
    }

    val settings: Flow<NajimSettings> = store.data.map { prefs ->
        NajimSettings(
            businessHeader = prefs[Keys.HEADER].orEmpty(),
            appearance = AppearanceMode.entries.firstOrNull { it.name == prefs[Keys.APPEARANCE] }
                ?: AppearanceMode.SYSTEM,
            onboardingFinished = prefs[Keys.ONBOARDING] ?: false,
            alerts = DebtAlertOptions(
                debtEnabled = prefs[Keys.ALERT_DEBT] ?: false,
                minimumDebtMinor = (prefs[Keys.ALERT_MINIMUM] ?: 10_000L).coerceAtLeast(1L),
                settlementDelayEnabled = prefs[Keys.ALERT_DELAY] ?: false,
                overdueAfterDays = (prefs[Keys.ALERT_DAYS] ?: 7).coerceIn(1, 365),
                systemNotificationsEnabled = prefs[Keys.ALERT_SYSTEM] ?: false
            )
        )
    }

    /** An allow-listed snapshot for authenticated portable backups. */
    suspend fun portableSnapshot(): ByteArray =
        PortableSettingsCodec.encode(settings.first())

    /** One atomic DataStore edit. OS notification permission is device-specific. */
    suspend fun restorePortable(raw: ByteArray) {
        val portable = PortableSettingsCodec.decode(raw)
        store.edit {
            it[Keys.HEADER] = portable.businessHeader
            it[Keys.ALERT_DEBT] = portable.alerts.debtEnabled
            it[Keys.ALERT_MINIMUM] = portable.alerts.minimumDebtMinor
            it[Keys.ALERT_DELAY] = portable.alerts.settlementDelayEnabled
            it[Keys.ALERT_DAYS] = portable.alerts.overdueAfterDays
            // Require fresh explicit user opt-in on the receiving device.
            it[Keys.ALERT_SYSTEM] = false
        }
    }

    suspend fun setAppearance(mode: AppearanceMode) {
        store.edit { it[Keys.APPEARANCE] = mode.name }
    }

    suspend fun finishOnboarding() {
        store.edit { it[Keys.ONBOARDING] = true }
    }

    suspend fun updateBusinessHeader(value: String) {
        val title = value.trim()
        require(title.length <= 80 && title.none(Char::isISOControl)) {
            "اسم المنشأة يجب ألا يتجاوز 80 حرفًا أو يحتوي محارف تحكم"
        }
        store.edit { it[Keys.HEADER] = title }
    }

    suspend fun updateAlerts(options: DebtAlertOptions) {
        store.edit {
            it[Keys.ALERT_DEBT] = options.debtEnabled
            it[Keys.ALERT_MINIMUM] = options.minimumDebtMinor
            it[Keys.ALERT_DELAY] = options.settlementDelayEnabled
            it[Keys.ALERT_DAYS] = options.overdueAfterDays
            it[Keys.ALERT_SYSTEM] = options.systemNotificationsEnabled
        }
    }
}

/**
 * Portable settings are deliberately allow-listed. Credentials, SQLCipher keys,
 * Google tokens, WorkManager schedules and recovery phrases are NEVER included.
 * The format has no filesystem paths and does not deserialize arbitrary objects.
 */
object PortableSettingsCodec {
    private const val VERSION = 1
    private const val LIMIT = 16 * 1024

    fun encode(value: NajimSettings): ByteArray {
        validate(value)
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use { bytes ->
            bytes.writeInt(VERSION)
            bytes.writeUTF(value.businessHeader)
            bytes.writeBoolean(value.alerts.debtEnabled)
            bytes.writeLong(value.alerts.minimumDebtMinor)
            bytes.writeBoolean(value.alerts.settlementDelayEnabled)
            bytes.writeInt(value.alerts.overdueAfterDays)
            bytes.writeBoolean(value.alerts.systemNotificationsEnabled)
        }
        return out.toByteArray().also {
            require(it.size in 1..LIMIT) { "حجم التفضيلات المحمولة غير صالح" }
        }
    }

    fun decode(raw: ByteArray): NajimSettings {
        require(raw.size in 1..LIMIT) { "حجم إعدادات النسخة الاحتياطية غير صالح" }
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(raw))
        require(input.readInt() == VERSION) { "إصدار إعدادات النسخة غير مدعوم" }
        val value = NajimSettings(
            businessHeader = input.readUTF(),
            alerts = DebtAlertOptions(
                debtEnabled = input.readBoolean(),
                minimumDebtMinor = input.readLong(),
                settlementDelayEnabled = input.readBoolean(),
                overdueAfterDays = input.readInt(),
                systemNotificationsEnabled = input.readBoolean()
            )
        )
        require(input.read() == -1) { "بيانات إعدادات إضافية غير متوقعة" }
        validate(value)
        return value
    }

    private fun validate(value: NajimSettings) {
        require(value.businessHeader.length <= 80 &&
            value.businessHeader.none(Char::isISOControl)) {
            "اسم المنشأة غير صالح في النسخة"
        }
        require(value.alerts.minimumDebtMinor > 0L &&
            value.alerts.overdueAfterDays in 1..365)
    }
}
