package com.najimwifi.app.data.local

import androidx.room.TypeConverter
import com.najimwifi.app.domain.model.CardMovementKind
import com.najimwifi.app.domain.model.ConsignmentEffect
import com.najimwifi.app.domain.model.FinancialEntryKind
import com.najimwifi.app.domain.model.PaymentMethod
import com.najimwifi.app.domain.model.SettlementMode

/** Persist stable enum names, never display labels or ordinals. */
class LedgerConverters {
    @TypeConverter
    fun cardMovementKindToString(value: CardMovementKind): String = value.name

    @TypeConverter
    fun stringToCardMovementKind(value: String): CardMovementKind =
        CardMovementKind.valueOf(value)

    @TypeConverter
    fun consignmentEffectToString(value: ConsignmentEffect): String = value.name

    @TypeConverter
    fun stringToConsignmentEffect(value: String): ConsignmentEffect =
        ConsignmentEffect.valueOf(value)

    @TypeConverter
    fun settlementModeToString(value: SettlementMode): String = value.name

    @TypeConverter
    fun stringToSettlementMode(value: String): SettlementMode =
        SettlementMode.valueOf(value)

    @TypeConverter
    fun financialKindToString(value: FinancialEntryKind): String = value.name

    @TypeConverter
    fun stringToFinancialKind(value: String): FinancialEntryKind =
        FinancialEntryKind.valueOf(value)

    @TypeConverter
    fun paymentMethodToString(value: PaymentMethod?): String? = value?.name

    @TypeConverter
    fun stringToPaymentMethod(value: String?): PaymentMethod? =
        value?.let(PaymentMethod::valueOf)
}