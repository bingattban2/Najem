package com.najimwifi.app.security

/** The user chooses when a backgrounded session requires authentication again. */
enum class LockPolicy(val label: String, val timeoutMillis: Long?) {
    OFF("غير مفعّل", null),
    IMMEDIATE("فور مغادرة التطبيق", 0L),
    AFTER_1_MINUTE("بعد دقيقة", 60_000L),
    AFTER_5_MINUTES("بعد 5 دقائق", 300_000L),
    AFTER_15_MINUTES("بعد 15 دقيقة", 900_000L),
    AFTER_30_MINUTES("بعد 30 دقيقة", 1_800_000L);

    val isEnabled: Boolean get() = timeoutMillis != null
}