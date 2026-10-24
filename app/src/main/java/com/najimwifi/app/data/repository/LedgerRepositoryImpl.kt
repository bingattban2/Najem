package com.najimwifi.app.data.repository

import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.entity.CardMovementEntity
import com.najimwifi.app.data.local.entity.FinancialEntryEntity
import kotlinx.coroutines.flow.Flow

/** Read-only ledger access; neither settlement lines nor cached totals create money. */
interface LedgerReader {
    fun observeDebt(customerId: String): Flow<Long>

    fun observePackageBalance(
        customerId: String,
        packageId: String
    ): Flow<Long>

    fun observeShopsBalance(
        packageId: String
    ): Flow<Long>

    fun observeCardJournal(
        customerId: String,
        packageId: String
    ): Flow<List<CardMovementEntity>>

    fun observeFinancialJournal(
        customerId: String
    ): Flow<List<FinancialEntryEntity>>

    suspend fun debtBefore(
        customerId: String,
        exclusiveEndEpochMillis: Long
    ): Long

    suspend fun balanceBefore(
        customerId: String,
        packageId: String,
        exclusiveEndEpochMillis: Long
    ): Long
}

class LedgerRepositoryImpl(
    private val database: NajimDatabase
) : LedgerReader {

    override fun observeDebt(customerId: String) =
        database.financialDao().observeDebt(customerId)

    override fun observePackageBalance(
        customerId: String,
        packageId: String
    ) = database.cardMovementDao().observeBalance(
        customerId,
        packageId
    )

    override fun observeShopsBalance(packageId: String) =
        database.cardMovementDao().observeAllShopsBalance(packageId)

    override fun observeCardJournal(
        customerId: String,
        packageId: String
    ) = database.cardMovementDao().observeJournal(
        customerId,
        packageId
    )

    override fun observeFinancialJournal(customerId: String) =
        database.financialDao().observeCustomerHistory(customerId)

    override suspend fun debtBefore(
        customerId: String,
        exclusiveEndEpochMillis: Long
    ) = database.financialDao().debtBefore(
        customerId,
        exclusiveEndEpochMillis
    )

    override suspend fun balanceBefore(
        customerId: String,
        packageId: String,
        exclusiveEndEpochMillis: Long
    ) = database.cardMovementDao().balanceBefore(
        customerId,
        packageId,
        exclusiveEndEpochMillis
    )
}