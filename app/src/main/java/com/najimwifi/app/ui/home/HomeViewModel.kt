package com.najimwifi.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.entity.CardMovementEntity
import com.najimwifi.app.data.local.entity.ColumnDefinitionEntity
import com.najimwifi.app.data.local.entity.CustomerEntity
import com.najimwifi.app.data.local.entity.OptionalColumnCellEntity
import com.najimwifi.app.data.local.entity.OptionalRowKind
import com.najimwifi.app.data.local.entity.PackageEntity
import com.najimwifi.app.data.repository.AccountingRepositoryImpl
import com.najimwifi.app.domain.contract.OwnerCorrectionRequest
import com.najimwifi.app.domain.contract.CollectionReversalRequest
import com.najimwifi.app.domain.contract.SettlementReversalRequest
import com.najimwifi.app.domain.contract.CatalogRepository
import com.najimwifi.app.domain.contract.DebtPaymentRequest
import com.najimwifi.app.domain.contract.DeliveryRequest
import com.najimwifi.app.domain.contract.PostingReceipt
import com.najimwifi.app.domain.contract.QuantityCorrectionRequest
import com.najimwifi.app.domain.contract.ReturnRequest
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.CardMovementKind
import com.najimwifi.app.domain.model.ColumnTarget
import com.najimwifi.app.domain.model.ColumnValueType
import com.najimwifi.app.domain.model.ConsignmentEffect
import com.najimwifi.app.domain.model.Customer
import com.najimwifi.app.domain.model.Package
import com.najimwifi.app.domain.rules.CalculatedSettlement
import com.najimwifi.app.domain.service.SettlementService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomePackageBalance(
    val pkg: Package,
    val quantity: Long
)

data class HomeCustomerRow(
    val customer: Customer,
    val cardCount: Long
)

data class HomeUiState(
    val packages: List<HomePackageBalance> = emptyList(),
    val customers: List<HomeCustomerRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

data class CustomerDetailsUiState(
    val customer: Customer? = null,
    val debtMinor: Long = 0L,
    val packages: List<HomePackageBalance> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

data class PackageJournalRow(
    val rowId: String,
    val rowKind: OptionalRowKind,
    val committedAtEpochMillis: Long,
    val operationLabel: String,
    val customerName: String,
    val packageName: String,
    val nominalValueMinor: Long,
    val withCustomer: String,
    val remaining: String?,
    val details: String
)

data class PackageLedgerUiState(
    val customer: Customer? = null,
    val pkg: Package? = null,
    val quantity: Long = 0L,
    val rows: List<PackageJournalRow> = emptyList(),
    val columns: List<ColumnDefinitionEntity> = emptyList(),
    val cells: List<OptionalColumnCellEntity> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

data class HomePostingState(
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

private data class JournalPresentation(
    val rows: List<PackageJournalRow>,
    val columns: List<ColumnDefinitionEntity>,
    val cells: List<OptionalColumnCellEntity>
)

/**
 * Reactive data presentation and transaction coordination.
 *
 * Card quantities are read from card movements.
 * Historical ledger figures are consulted only to maintain compatibility
 * with stored settlement records; they are not a customer-facing debt.
 * All posting and settlement arithmetic is delegated
 * to SettlementService and AccountingRepositoryImpl.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val database: NajimDatabase,
    private val catalog: CatalogRepository,
    private val settlementService: SettlementService =
        SettlementService(
            AccountingRepositoryImpl(database)
        ),
    private val ownerVerifier: suspend (String) -> String = {
        throw IllegalStateException("لم يتم ربط صلاحية المالك")
    }
) : ViewModel() {

    private val mutablePostingState =
        MutableStateFlow(HomePostingState())

    val postingState: StateFlow<HomePostingState> =
        mutablePostingState

    private fun checkedBalance(value: Long): Long {
        require(value >= 0L) {
            "تم العثور على رصيد سالب؛ يلزم مراجعة السجل"
        }
        return value
    }

    private fun CustomerEntity.toDomain() = Customer(
        id = id,
        name = name,
        location = location,
        phone = phone,
        isActive = isActive,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis
    )

    private fun PackageEntity.toDomain() = Package(
        id = id,
        name = name,
        nominalValueMinor = nominalValueMinor,
        generalSettlementPriceMinor =
            generalSettlementPriceMinor,
        colorArgb = colorArgb,
        isActive = isActive,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis
    )

    private fun observeAllPackageBalances(): Flow<List<HomePackageBalance>> = combine(
        catalog.observePackages(),
        database.cardMovementDao().observePackageTotals()
    ) { packages, totals ->
        val quantities = totals.associate { it.packageId to checkedBalance(it.quantity) }
        packages.map { pkg -> HomePackageBalance(pkg, quantities[pkg.id] ?: 0L) }
    }

    // The customer list tracks only card balances, not cumulative debts.
    private fun observeAllCustomersWithCards(): Flow<List<HomeCustomerRow>> = combine(
        catalog.observeCustomers(),
        database.cardMovementDao().observeCustomerTotals()
    ) { customers, totals ->
        val quantities = totals.associate { it.customerId to checkedBalance(it.quantity) }
        customers.map { customer ->
            HomeCustomerRow(customer, quantities[customer.id] ?: 0L)
        }
    }

    val state: StateFlow<HomeUiState> = combine(
        observeAllPackageBalances(),
        observeAllCustomersWithCards()
    ) { packages, customers ->
        HomeUiState(
            packages = packages,
            customers = customers,
            loading = false
        )
    }.catch { error ->
        emit(
            HomeUiState(
                loading = false,
                error = error.message
                    ?: "تعذر قراءة بيانات الرئيسية"
            )
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        HomeUiState()
    )

    fun observeCustomerDetails(
        customerId: String
    ): Flow<CustomerDetailsUiState> {
        require(customerId.isNotBlank())

        val packageBalances = combine(
            catalog.observePackages(),
            database.cardMovementDao().observeCustomerPackageTotals(customerId)
        ) { packages, totals ->
            val quantities = totals.associate { it.packageId to checkedBalance(it.quantity) }
            packages.map { pkg -> HomePackageBalance(pkg, quantities[pkg.id] ?: 0L) }
        }

        return combine(
            database.customerDao()
                .observeById(customerId),
            database.financialDao()
                .observeDebt(customerId),
            packageBalances
        ) { customer, debt, packages ->
            CustomerDetailsUiState(
                customer = customer?.toDomain(),
                debtMinor = checkedBalance(debt),
                packages = packages,
                loading = false
            )
        }.catch { error ->
            emit(
                CustomerDetailsUiState(
                    loading = false,
                    error = error.message
                        ?: "تعذر تحميل بيانات العميل"
                )
            )
        }
    }

    fun observePackageLedger(
        customerId: String,
        packageId: String
    ): Flow<PackageLedgerUiState> {
        require(
            customerId.isNotBlank() &&
                packageId.isNotBlank()
        )

        val journal = combine(
            database.cardMovementDao().observeJournal(
                customerId,
                packageId
            ),
            database.settlementDao()
                .observeCustomerSettlements(customerId),
            database.settlementDao()
                .observeLinesForCustomerPackage(
                    customerId,
                    packageId
                ),
            database.columnDefinitionDao()
                .observeColumns(
                    ColumnTarget.PACKAGE_JOURNAL
                ),
            database.columnDefinitionDao()
                .observePackageJournalCells(
                    customerId,
                    packageId
                )
        ) { movements, groups, lines, columns, cells ->

            val groupTimes = groups.associate {
                it.id to it.committedAtEpochMillis
            }

            val movementRows = movements
                .filter {
                    it.kind !=
                        CardMovementKind.SETTLEMENT_SALE
                }
                .map { movement ->

                    val sign = when (movement.effect) {
                        ConsignmentEffect.INCREASE -> "+"
                        ConsignmentEffect.DECREASE -> "-"
                    }

                    val operation = when (movement.kind) {
                        CardMovementKind.DELIVERY ->
                            "تسليم"

                        CardMovementKind.UNSOLD_RETURN ->
                            "إرجاع غير مباع"

                        CardMovementKind.CORRECTION ->
                            "تصحيح"

                        CardMovementKind.SETTLEMENT_SALE ->
                            "محاسبة"
                    }

                    PackageJournalRow(
                        rowId = movement.id,
                        rowKind =
                            OptionalRowKind.CARD_MOVEMENT,
                        committedAtEpochMillis =
                            movement.committedAtEpochMillis,
                        operationLabel = operation,
                        customerName =
                            movement.customerSnapshot.name,
                        packageName =
                            movement.packageSnapshot.name,
                        nominalValueMinor =
                            movement.packageSnapshot
                                .nominalValueMinor,
                        withCustomer =
                            "$sign${movement.quantity}",
                        remaining = null,
                        details =
                            movement.referenceNumber +
                                (
                                    movement.correctionReason
                                        ?.let { " • $it" }
                                        ?: ""
                                )
                    )
                }

            val settlementRows = lines.mapNotNull { line ->
                val at = groupTimes[line.settlementGroupId]
                    ?: return@mapNotNull null

                PackageJournalRow(
                    rowId = line.id,
                    rowKind =
                        OptionalRowKind.SETTLEMENT_LINE,
                    committedAtEpochMillis = at,
                    operationLabel = "محاسبة",
                    customerName =
                        line.customerSnapshot.name,
                    packageName =
                        line.packageSnapshot.name,
                    nominalValueMinor =
                        line.packageSnapshot.nominalValueMinor,
                    withCustomer = line.stockBefore.toString(),
                    remaining = line.stockRemaining.toString(),
                    details =
                        "مباع: ${line.soldQuantity}" +
                            " • مستحق: ${line.amountDueMinor}"
                )
            }

            JournalPresentation(
                rows = (movementRows + settlementRows)
                    .sortedWith(
                        compareByDescending<PackageJournalRow> {
                            it.committedAtEpochMillis
                        }.thenByDescending {
                            it.rowId
                        }
                    ),
                columns = columns,
                cells = cells
            )
        }

        return combine(
            database.customerDao()
                .observeById(customerId),
            database.packageDao()
                .observeById(packageId),
            database.cardMovementDao()
                .observeBalance(
                    customerId,
                    packageId
                ),
            journal
        ) { customer, pkg, balance, presentation ->
            PackageLedgerUiState(
                customer = customer?.toDomain(),
                pkg = pkg?.toDomain(),
                quantity = checkedBalance(balance),
                rows = presentation.rows,
                columns = presentation.columns,
                cells = presentation.cells,
                loading = false
            )
        }.catch { error ->
            emit(
                PackageLedgerUiState(
                    loading = false,
                    error = error.message
                        ?: "تعذر قراءة سجل الباقة"
                )
            )
        }
    }

    suspend fun saveJournalCell(
        customerId: String,
        packageId: String,
        row: PackageJournalRow,
        columnId: String,
        enteredValue: String
    ) {
        require(
            customerId.isNotBlank() &&
                packageId.isNotBlank()
        )

        database.withTransaction {
            val dao = database.columnDefinitionDao()

            val column = requireNotNull(
                dao.findColumn(columnId)
            ) {
                "العمود غير موجود"
            }

            require(
                column.target ==
                    ColumnTarget.PACKAGE_JOURNAL &&
                    column.requiredKey == null &&
                    column.isActive
            ) {
                "لا يمكن تعديل هذا العمود"
            }

            val validRow = when (row.rowKind) {
                OptionalRowKind.CARD_MOVEMENT -> {
                    val movement = database.cardMovementDao()
                        .findById(row.rowId)

                    movement?.customerId == customerId &&
                        movement.packageId == packageId
                }

                OptionalRowKind.SETTLEMENT_LINE ->
                    database.settlementDao()
                        .findLineForCustomerPackage(
                            row.rowId,
                            customerId,
                            packageId
                        ) != null

                else -> false
            }

            require(validRow) {
                "السجل غير موجود أو لا يخص هذه الباقة"
            }

            val cleaned = enteredValue.trim()

            if (cleaned.isEmpty()) {
                dao.removeCell(
                    columnId,
                    row.rowKind.name,
                    row.rowId
                )
                return@withTransaction
            }

            val cell = when (column.valueType) {
                ColumnValueType.TEXT -> {
                    require(cleaned.length <= 500) {
                        "الحد الأقصى للنص 500 حرف"
                    }

                    OptionalColumnCellEntity(
                        columnId = column.id,
                        rowKind = row.rowKind,
                        rowId = row.rowId,
                        valueType = ColumnValueType.TEXT,
                        textValue = cleaned
                    )
                }

                ColumnValueType.WHOLE_NUMBER -> {
                    val number = cleaned.toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "أدخل عددًا صحيحًا ضمن النطاق المسموح"
                        )

                    OptionalColumnCellEntity(
                        columnId = column.id,
                        rowKind = row.rowKind,
                        rowId = row.rowId,
                        valueType =
                            ColumnValueType.WHOLE_NUMBER,
                        numberValue = number
                    )
                }

                ColumnValueType.MONEY_MINOR ->
                    throw IllegalArgumentException(
                        "لا يمكن تحرير قيم مالية اختيارية"
                    )
            }

            dao.saveCell(cell)
        }
    }

    /**
     * Quantity corrections are limited to deliveries and
     * unsold returns. Already reversed source records are
     * hidden from normal selection.
     *
     * The repository remains responsible for final
     * transaction validation.
     */
    fun observeCorrectionCandidates(
        customerId: String,
        packageId: String
    ): Flow<List<CardMovementEntity>> {
        return database.cardMovementDao()
            .observeJournal(customerId, packageId)
            .map { movements ->

                val reversedIds = movements
                    .mapNotNull {
                        it.reversesMovementId
                    }
                    .toSet()

                movements.filter { movement ->
                    (
                        movement.kind ==
                            CardMovementKind.DELIVERY ||
                            movement.kind ==
                            CardMovementKind.UNSOLD_RETURN
                    ) && movement.id !in reversedIds
                }
            }
    }


    /** Only standalone collections can be reversed individually. */
    fun observeCollectionCorrectionCandidates(
        customerId: String
    ) = database.financialDao().observeCustomerHistory(customerId).map { history ->
        val already = history.mapNotNull { it.reversesEntryId }.toSet()
        history.filter {
            it.kind == com.najimwifi.app.domain.model.FinancialEntryKind.COLLECTION &&
                it.settlementGroupId == null && it.id !in already
        }
    }

    /** Settlement payments and sales are reversed together, never per line. */
    fun observeSettlementCorrectionCandidates(
        customerId: String
    ) = combine(
        database.settlementDao().observeCustomerSettlements(customerId),
        database.auditDao().observeSubjectHistory(customerId)
    ) { groups, audits ->
        val already = audits.filter {
            it.action == com.najimwifi.app.domain.model.AuditAction.CORRECTION
        }.mapNotNull { it.correctionOfId }.toSet()
        groups.filter {
            it.id !in already &&
                (it.totalAmountDueMinor > 0L || it.singlePaymentMinor > 0L)
        }
    }

    suspend fun isCentralStockEnabled(
        packageId: String
    ): Boolean {
        require(packageId.isNotBlank())

        return database.centralStockDao()
            .findPolicy(packageId)
            ?.enabled == true
    }

    fun previewSettlement(
        request: SettlementRequest
    ): CalculatedSettlement =
        settlementService.preview(request)

    fun clearPostingMessage() {
        if (!mutablePostingState.value.busy) {
            mutablePostingState.value = HomePostingState()
        }
    }

    /**
     * A single active posting request is permitted from
     * this ViewModel at a time.
     *
     * The operationId is created by each dialog and kept
     * stable during a retry of that confirmed request.
     */
    private fun post(
        operation: suspend () -> PostingReceipt,
        onSuccess: () -> Unit
    ) {
        if (mutablePostingState.value.busy) return

        mutablePostingState.value = HomePostingState(
            busy = true
        )

        viewModelScope.launch {
            val receipt = try {
                operation()
            } catch (cancelled: CancellationException) {
                mutablePostingState.value = HomePostingState()
                throw cancelled
            } catch (error: Exception) {
                mutablePostingState.value = HomePostingState(
                    busy = false,
                    error = error.message
                        ?: "تعذر حفظ العملية؛ لم يتم التأكيد"
                )
                return@launch
            }

            mutablePostingState.value = HomePostingState(
                busy = false,
                message = "تمت العملية بنجاح"
            )

            onSuccess()
        }
    }

    fun confirmDelivery(
        request: DeliveryRequest,
        onSuccess: () -> Unit
    ) {
        post(
            operation = {
                settlementService.confirmDelivery(request)
            },
            onSuccess = onSuccess
        )
    }

    fun confirmSettlement(
        request: SettlementRequest,
        onSuccess: () -> Unit
    ) {
        post(
            operation = {
                settlementService.confirmSettlement(request)
            },
            onSuccess = onSuccess
        )
    }

    fun confirmPayment(
        request: DebtPaymentRequest,
        onSuccess: () -> Unit
    ) {
        post(
            operation = {
                settlementService.confirmPayment(request)
            },
            onSuccess = onSuccess
        )
    }

    fun confirmReturn(
        request: ReturnRequest,
        onSuccess: () -> Unit
    ) {
        post(
            operation = {
                settlementService.confirmReturn(request)
            },
            onSuccess = onSuccess
        )
    }

    suspend fun verifyCorrectionOwner(password: String): String =
        ownerVerifier(password)

    fun confirmCorrection(
        request: OwnerCorrectionRequest,
        onSuccess: () -> Unit
    ) {
        post(
            operation = {
                when (request) {
                    is QuantityCorrectionRequest ->
                        settlementService.confirmQuantityCorrection(request)
                    is CollectionReversalRequest ->
                        settlementService.confirmCollectionReversal(request)
                    is SettlementReversalRequest ->
                        settlementService.confirmSettlementReversal(request)
                }
            },
            onSuccess = onSuccess
        )
    }
}