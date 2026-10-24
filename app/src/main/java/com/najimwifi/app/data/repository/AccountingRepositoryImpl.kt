package com.najimwifi.app.data.repository

import androidx.room.withTransaction
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.entity.AuditEventEntity
import com.najimwifi.app.data.local.entity.CardMovementEntity
import com.najimwifi.app.data.local.entity.CentralStockEntity
import com.najimwifi.app.data.local.entity.CustomerEntity
import com.najimwifi.app.data.local.entity.FinancialEntryEntity
import com.najimwifi.app.data.local.entity.PackageEntity
import com.najimwifi.app.data.local.entity.SettlementGroupEntity
import com.najimwifi.app.data.local.entity.SettlementLineEntity
import com.najimwifi.app.domain.contract.CollectionReversalRequest
import com.najimwifi.app.domain.contract.SettlementReversalRequest
import com.najimwifi.app.domain.contract.AccountingRepository
import com.najimwifi.app.domain.contract.DebtPaymentRequest
import com.najimwifi.app.domain.contract.DeliveryRequest
import com.najimwifi.app.domain.contract.PostingReceipt
import com.najimwifi.app.domain.contract.QuantityCorrectionRequest
import com.najimwifi.app.domain.contract.ReturnRequest
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.AuditAction
import com.najimwifi.app.domain.model.AuditOutcome
import com.najimwifi.app.domain.model.CardMovementKind
import com.najimwifi.app.domain.model.CentralStockEffect
import com.najimwifi.app.domain.model.CentralStockKind
import com.najimwifi.app.domain.model.ConsignmentEffect
import com.najimwifi.app.domain.model.CustomerSnapshot
import com.najimwifi.app.domain.model.FinancialEntryKind
import com.najimwifi.app.domain.model.PackageSnapshot
import com.najimwifi.app.domain.model.SettlementMode
import com.najimwifi.app.domain.rules.AccountingRules
import com.najimwifi.app.domain.rules.PackageBalance
import java.util.UUID

/** Room is the source of truth. Each public write is one serialized transaction. */
class AccountingRepositoryImpl(
    private val db: NajimDatabase
) : AccountingRepository {

    private fun newId() = UUID.randomUUID().toString()

    private fun reference(prefix: String) = "$prefix-${newId()}"

    private fun requireOperation(id: String, confirmed: Boolean) {
        require(id.isNotBlank() && confirmed) {
            "A confirmed stable operation ID is required"
        }
    }

    private fun CustomerEntity.snapshot() = CustomerSnapshot(
        name,
        location
    )

    private fun PackageEntity.snapshot() = PackageSnapshot(
        name,
        nominalValueMinor,
        generalSettlementPriceMinor,
        colorArgb
    )

    private suspend fun customer(id: String): CustomerEntity =
        requireNotNull(db.customerDao().findById(id)) {
            "Unknown customer"
        }

    private suspend fun packageFor(id: String): PackageEntity =
        requireNotNull(db.packageDao().findById(id)) {
            "Unknown package"
        }

    private suspend fun priorOperation(
        id: String,
        action: AuditAction
    ): Boolean {
        val audit = db.auditDao().findById(id) ?: return false

        require(
            audit.action == action &&
                audit.outcome == AuditOutcome.COMMITTED
        ) {
            "Operation ID has already been used for a different action"
        }
        return true
    }

    private suspend fun audit(
        id: String,
        action: AuditAction,
        subjectId: String,
        at: Long,
        description: String,
        actor: String? = null,
        correctionOfId: String? = null
    ) {
        db.auditDao().insert(
            AuditEventEntity(
                id = id,
                operationId = id,
                occurredAtEpochMillis = at,
                action = action,
                outcome = AuditOutcome.COMMITTED,
                subjectId = subjectId,
                description = description,
                actorId = actor,
                correctionOfId = correctionOfId
            )
        )
    }

    private suspend fun result(
        operationId: String,
        referenceNumber: String?,
        customerId: String,
        packageIds: Iterable<String> = emptyList(),
        due: Long = 0L,
        paid: Long = 0L
    ): PostingReceipt {
        val balances = packageIds.distinct().associateWith {
            db.cardMovementDao().currentBalance(customerId, it)
        }

        return PostingReceipt(
            operationId,
            referenceNumber,
            customerId,
            db.financialDao().currentDebt(customerId),
            balances,
            due,
            paid
        )
    }

    override suspend fun deliver(
        request: DeliveryRequest
    ): PostingReceipt = db.withTransaction {

        requireOperation(request.operationId, request.confirmed)

        if (priorOperation(request.operationId, AuditAction.DELIVERY)) {
            val old = db.cardMovementDao()
                .findByOperation(request.operationId)
                .singleOrNull {
                    it.kind == CardMovementKind.DELIVERY
                } ?: error("Committed delivery record is missing")

            require(
                old.customerId == request.customerId &&
                    old.packageId == request.packageId &&
                    old.quantity == request.quantity &&
                    old.packageSnapshot == request.expectedPackage
            ) {
                "Operation ID reused with different delivery values"
            }

            // A replay must not mask a missing or mismatched warehouse posting.
            val stockLinks = db.centralStockDao().findByOperation(request.operationId)
            require(stockLinks.size <= 1 && stockLinks.all {
                it.kind == CentralStockKind.DELIVERY_TO_CUSTOMER &&
                    it.effect == CentralStockEffect.DECREASE &&
                    it.packageId == old.packageId &&
                    it.customerId == old.customerId &&
                    it.quantity == old.quantity
            }) { "Warehouse delivery posting does not match its shop movement" }

            return@withTransaction result(
                request.operationId,
                old.referenceNumber,
                request.customerId,
                listOf(request.packageId)
            )
        }

        // Independent warehouse supply/opening operations must never share an ID
        // with a new customer delivery (even when stock tracking is disabled).
        require(db.centralStockDao().findByOperation(request.operationId).isEmpty()) {
            "Operation ID already used by a warehouse movement"
        }

        val customer = customer(request.customerId)
        require(customer.isActive) { "Customer is inactive" }

        val pkg = packageFor(request.packageId)
        require(pkg.snapshot() == request.expectedPackage) {
            "Package changed: reopen delivery"
        }

        val before = db.cardMovementDao()
            .currentBalance(customer.id, pkg.id)

        require(before == request.expectedShopBalance) {
            "Outdated shop balance"
        }

        // This simplified app tracks only cards with each customer.
        // Preserve historical warehouse rows, but do not require or mutate
        // an old optional warehouse balance for any NEW delivery.
        AccountingRules.deliver(
            before,
            request.quantity,
            pkg.isActive,
            centralOnHand = null
        )

        val now = System.currentTimeMillis()
        val ref = reference("DEL")

        db.cardMovementDao().insert(
            CardMovementEntity(
                id = newId(),
                operationId = request.operationId,
                referenceNumber = ref,
                customerId = customer.id,
                packageId = pkg.id,
                kind = CardMovementKind.DELIVERY,
                effect = ConsignmentEffect.INCREASE,
                quantity = request.quantity,
                committedAtEpochMillis = now,
                customerSnapshot = customer.snapshot(),
                packageSnapshot = pkg.snapshot()
            )
        )

        audit(
            request.operationId,
            AuditAction.DELIVERY,
            customer.id,
            now,
            "Delivery $ref"
        )

        result(
            request.operationId,
            ref,
            customer.id,
            listOf(pkg.id)
        )
    }

    override suspend fun settle(
        request: SettlementRequest
    ): PostingReceipt = db.withTransaction {

        requireOperation(request.operationId, request.confirmed)

        if (priorOperation(request.operationId, AuditAction.SETTLEMENT)) {
            val old = db.settlementDao()
                .findGroupByOperation(request.operationId)
                ?: error("Committed settlement group is missing")

            val lines = db.settlementDao().findLines(old.id)

            require(
                old.customerId == request.customerId &&
                    old.mode == request.mode &&
                    old.previousDebtMinor == request.expectedPreviousDebtMinor &&
                    old.singlePaymentMinor == request.singlePaymentMinor &&
                    lines.size == request.counts.size &&
                    request.counts.all { input ->
                        lines.any { line ->
                            line.packageId == input.packageId &&
                                line.stockBefore == input.expectedShopBalance &&
                                line.stockRemaining == input.remainingCount &&
                                line.packageSnapshot == input.expectedPackage
                        }
                    }
            ) {
                "Operation ID reused with different settlement values"
            }

            val oldCollection = db.financialDao().findGroupEntry(
                old.id,
                FinancialEntryKind.COLLECTION
            )

            require(
                (oldCollection == null && request.singlePaymentMinor == 0L) ||
                    (oldCollection != null &&
                        oldCollection.paymentMethod == request.paymentMethod &&
                        oldCollection.note == request.paymentNote)
            ) {
                "Operation ID reused with different payment details"
            }

            return@withTransaction result(
                request.operationId,
                oldCollection?.receiptNumber ?: old.id,
                request.customerId,
                lines.map { it.packageId },
                old.totalAmountDueMinor,
                old.singlePaymentMinor
            )
        }

        val customer = customer(request.customerId)

        require(
            request.counts.map { it.packageId }.distinct().size ==
                request.counts.size
        ) {
            "Duplicate package count"
        }

        val positive = db.consignmentSnapshotDao()
            .positiveBalances(customer.id)

        val selected = when (request.mode) {
            SettlementMode.ONE -> {
                require(!request.selectedPackageId.isNullOrBlank())

                positive.filter {
                    it.packageId == request.selectedPackageId
                }.also {
                    require(it.size == 1) {
                        "Selected package has no cards"
                    }
                }
            }

            SettlementMode.ALL -> {
                require(request.selectedPackageId == null)
                require(positive.isNotEmpty()) {
                    "No cards; use standalone debt payment"
                }
                positive
            }
        }

        require(
            selected.map { it.packageId }.toSet() ==
                request.counts.map { it.packageId }.toSet()
        ) {
            "Settlement scope changed; reopen physical count"
        }

        val byId = request.counts.associateBy { it.packageId }

        val balances = selected.map { row ->
            val input = requireNotNull(byId[row.packageId])
            val pkg = packageFor(row.packageId)

            require(
                input.expectedShopBalance == row.onHand &&
                    input.expectedPackage == pkg.snapshot()
            ) {
                "Stock or price changed"
            }

            PackageBalance(
                pkg.id,
                pkg.snapshot(),
                row.onHand,
                pkg.isActive
            )
        }

        val debt = db.financialDao().currentDebt(customer.id)

        require(debt == request.expectedPreviousDebtMinor) {
            "Previous debt changed"
        }

        val calculated = AccountingRules.calculateSettlement(
            request.mode,
            balances,
            request.selectedPackageId,
            request.counts.associate {
                it.packageId to it.remainingCount
            },
            debt,
            request.singlePaymentMinor
        )

        val now = System.currentTimeMillis()
        val groupId = newId()
        val paymentId = if (request.singlePaymentMinor > 0L) {
            newId()
        } else {
            null
        }

        db.settlementDao().insertGroup(
            SettlementGroupEntity(
                id = groupId,
                operationId = request.operationId,
                customerId = customer.id,
                mode = request.mode,
                committedAtEpochMillis = now,
                customerSnapshot = customer.snapshot(),
                previousDebtMinor = debt,
                totalAmountDueMinor = calculated.newAmountDue.minorUnits,
                singlePaymentMinor = request.singlePaymentMinor,
                endingDebtMinor = calculated.endingDebt.minorUnits,
                paymentEntryId = paymentId
            )
        )

        val lines = calculated.lines.map { line ->
            SettlementLineEntity(
                id = newId(),
                settlementGroupId = groupId,
                packageId = line.packageId,
                stockBefore = line.before,
                stockRemaining = line.remaining,
                soldQuantity = line.sold,
                amountDueMinor = line.amountDue.minorUnits,
                customerSnapshot = customer.snapshot(),
                packageSnapshot = line.snapshot
            )
        }

        db.settlementDao().insertLines(lines)

        for (line in lines) {
            if (line.soldQuantity > 0L) {
                db.cardMovementDao().insert(
                    CardMovementEntity(
                        id = newId(),
                        operationId = request.operationId,
                        referenceNumber = reference("SALE"),
                        customerId = customer.id,
                        packageId = line.packageId,
                        kind = CardMovementKind.SETTLEMENT_SALE,
                        effect = ConsignmentEffect.DECREASE,
                        quantity = line.soldQuantity,
                        committedAtEpochMillis = now,
                        customerSnapshot = customer.snapshot(),
                        packageSnapshot = line.packageSnapshot,
                        settlementLineId = line.id
                    )
                )
            }
        }

        if (calculated.newAmountDue.minorUnits > 0L) {
            db.financialDao().insert(
                FinancialEntryEntity(
                    id = newId(),
                    operationId = request.operationId,
                    customerId = customer.id,
                    kind = FinancialEntryKind.SETTLEMENT_RECEIVABLE,
                    amountMinor = calculated.newAmountDue.minorUnits,
                    committedAtEpochMillis = now,
                    customerSnapshot = customer.snapshot(),
                    settlementGroupId = groupId
                )
            )
        }

        var receipt: String? = null

        if (paymentId != null) {
            receipt = reference("REC")

            db.financialDao().insert(
                FinancialEntryEntity(
                    id = paymentId,
                    operationId = request.operationId,
                    customerId = customer.id,
                    kind = FinancialEntryKind.COLLECTION,
                    amountMinor = request.singlePaymentMinor,
                    committedAtEpochMillis = now,
                    customerSnapshot = customer.snapshot(),
                    settlementGroupId = groupId,
                    receiptNumber = receipt,
                    paymentMethod = request.paymentMethod,
                    note = request.paymentNote
                )
            )
        }

        require(
            db.settlementDao().sumLineAmounts(groupId) ==
                calculated.newAmountDue.minorUnits
        )

        require(
            db.financialDao().currentDebt(customer.id) ==
                calculated.endingDebt.minorUnits
        )

        audit(
            request.operationId,
            AuditAction.SETTLEMENT,
            customer.id,
            now,
            "Settlement $groupId"
        )

        result(
            request.operationId,
            receipt ?: groupId,
            customer.id,
            lines.map { it.packageId },
            calculated.newAmountDue.minorUnits,
            request.singlePaymentMinor
        )
    }

    override suspend fun payDebt(
        request: DebtPaymentRequest
    ): PostingReceipt = db.withTransaction {

        requireOperation(request.operationId, request.confirmed)

        if (priorOperation(request.operationId, AuditAction.PAYMENT)) {
            val entries = db.financialDao()
                .findByOperation(request.operationId)

            val old = entries.singleOrNull()

            require(
                (old == null && request.amountMinor == 0L) ||
                    (old != null &&
                        old.kind == FinancialEntryKind.COLLECTION &&
                        old.customerId == request.customerId &&
                        old.amountMinor == request.amountMinor &&
                        old.paymentMethod == request.method &&
                        old.note == request.note)
            ) {
                "Operation ID reused with different payment"
            }

            return@withTransaction result(
                request.operationId,
                old?.receiptNumber,
                request.customerId,
                paid = request.amountMinor
            )
        }

        val customer = customer(request.customerId)
        val debt = db.financialDao().currentDebt(customer.id)

        require(debt == request.expectedDebtMinor) {
            "Debt changed; reopen payment"
        }

        val after = AccountingRules.payExistingDebt(
            debt,
            request.amountMinor
        )

        val now = System.currentTimeMillis()
        var receipt: String? = null

        if (request.amountMinor > 0L) {
            receipt = reference("REC")

            db.financialDao().insert(
                FinancialEntryEntity(
                    id = newId(),
                    operationId = request.operationId,
                    customerId = customer.id,
                    kind = FinancialEntryKind.COLLECTION,
                    amountMinor = request.amountMinor,
                    committedAtEpochMillis = now,
                    customerSnapshot = customer.snapshot(),
                    receiptNumber = receipt,
                    paymentMethod = request.method,
                    note = request.note
                )
            )
        }

        require(
            db.financialDao().currentDebt(customer.id) ==
                after.minorUnits
        )

        audit(
            request.operationId,
            AuditAction.PAYMENT,
            customer.id,
            now,
            if (receipt == null) {
                "Zero payment confirmed"
            } else {
                "Collection $receipt"
            }
        )

        result(
            request.operationId,
            receipt,
            customer.id,
            paid = request.amountMinor
        )
    }

    override suspend fun returnUnsold(
        request: ReturnRequest
    ): PostingReceipt = db.withTransaction {

        requireOperation(request.operationId, request.confirmed)

        if (priorOperation(request.operationId, AuditAction.RETURN)) {
            val old = db.cardMovementDao()
                .findByOperation(request.operationId)
                .singleOrNull {
                    it.kind == CardMovementKind.UNSOLD_RETURN
                } ?: error("Committed return is missing")

            val warehouseLinks = db.centralStockDao()
                .findByOperation(request.operationId)
            require(warehouseLinks.size <= 1 && warehouseLinks.all {
                it.kind == CentralStockKind.RETURN_FROM_CUSTOMER &&
                    it.effect == CentralStockEffect.INCREASE &&
                    it.packageId == old.packageId &&
                    it.customerId == old.customerId &&
                    it.quantity == old.quantity
            }) { "Warehouse return posting does not match its shop movement" }
            val centralPosted = warehouseLinks.isNotEmpty()

            require(
                old.customerId == request.customerId &&
                    old.packageId == request.packageId &&
                    old.quantity == request.quantity &&
                    old.packageSnapshot == request.expectedPackage &&
                    centralPosted == request.returnToCentral
            ) {
                "Operation ID reused with different return"
            }

            return@withTransaction result(
                request.operationId,
                old.referenceNumber,
                request.customerId,
                listOf(request.packageId)
            )
        }

        require(db.centralStockDao().findByOperation(request.operationId).isEmpty()) {
            "Operation ID already used by a warehouse movement"
        }

        val customer = customer(request.customerId)
        val pkg = packageFor(request.packageId)

        require(pkg.snapshot() == request.expectedPackage) {
            "Package changed"
        }

        val before = db.cardMovementDao()
            .currentBalance(customer.id, pkg.id)

        require(before == request.expectedShopBalance) {
            "Outdated shop balance"
        }

        val tracked = db.centralStockDao()
            .findPolicy(pkg.id)?.enabled == true

        require(!request.returnToCentral || tracked) {
            "Central tracking disabled"
        }

        val centralBefore = if (tracked) {
            db.centralStockDao().currentBalance(pkg.id)
        } else {
            null
        }

        val move = AccountingRules.returnUnsold(
            before,
            request.quantity,
            centralBefore,
            request.returnToCentral
        )

        val now = System.currentTimeMillis()
        val ref = reference("RET")

        db.cardMovementDao().insert(
            CardMovementEntity(
                id = newId(),
                operationId = request.operationId,
                referenceNumber = ref,
                customerId = customer.id,
                packageId = pkg.id,
                kind = CardMovementKind.UNSOLD_RETURN,
                effect = ConsignmentEffect.DECREASE,
                quantity = request.quantity,
                committedAtEpochMillis = now,
                customerSnapshot = customer.snapshot(),
                packageSnapshot = pkg.snapshot()
            )
        )

        if (request.returnToCentral) {
            db.centralStockDao().insertMovement(
                CentralStockEntity(
                    id = newId(),
                    operationId = request.operationId,
                    packageId = pkg.id,
                    kind = CentralStockKind.RETURN_FROM_CUSTOMER,
                    effect = CentralStockEffect.INCREASE,
                    quantity = request.quantity,
                    balanceBefore = requireNotNull(centralBefore),
                    balanceAfter = requireNotNull(move.centralAfter),
                    committedAtEpochMillis = now,
                    packageSnapshot = pkg.snapshot(),
                    customerId = customer.id
                )
            )
        }

        audit(
            request.operationId,
            AuditAction.RETURN,
            customer.id,
            now,
            "Unsold return $ref"
        )

        result(
            request.operationId,
            ref,
            customer.id,
            listOf(pkg.id)
        )
    }

    override suspend fun reverseQuantity(
        request: QuantityCorrectionRequest
    ): PostingReceipt = db.withTransaction {

        requireOperation(request.operationId, request.confirmed)

        require(
            request.reason.isNotBlank() &&
                request.authorizedBy.isNotBlank()
        )

        if (priorOperation(request.operationId, AuditAction.CORRECTION)) {
            val correction = db.cardMovementDao()
                .findByOperation(request.operationId)
                .singleOrNull {
                    it.kind == CardMovementKind.CORRECTION
                } ?: error("Committed correction is missing")

            require(
                correction.reversesMovementId == request.originalMovementId &&
                    correction.correctionReason == request.reason &&
                    correction.authorizedBy == request.authorizedBy
            ) {
                "Operation ID reused with different correction"
            }

            return@withTransaction result(
                request.operationId,
                correction.referenceNumber,
                correction.customerId,
                listOf(correction.packageId)
            )
        }

        val original = db.cardMovementDao()
            .findById(request.originalMovementId)
            ?: error("Original movement not found")

        require(
            original.kind == CardMovementKind.DELIVERY ||
                original.kind == CardMovementKind.UNSOLD_RETURN
        ) {
            "Settlement sales require linked financial reversal; not supported here"
        }

        val before = db.cardMovementDao()
            .currentBalance(
                original.customerId,
                original.packageId
            )

        val reversed = if (
            original.effect == ConsignmentEffect.INCREASE
        ) {
            ConsignmentEffect.DECREASE
        } else {
            ConsignmentEffect.INCREASE
        }

        val after = if (
            reversed == ConsignmentEffect.INCREASE
        ) {
            Math.addExact(before, original.quantity)
        } else {
            Math.subtractExact(before, original.quantity)
        }

        require(after >= 0L) {
            "Correction would create negative consignment"
        }

        val central = db.centralStockDao()
            .findByOperation(original.operationId)

        require(central.size <= 1) {
            "Ambiguous original central stock movements"
        }

        val now = System.currentTimeMillis()
        val ref = reference("COR")

        db.cardMovementDao().insert(
            CardMovementEntity(
                id = newId(),
                operationId = request.operationId,
                referenceNumber = ref,
                customerId = original.customerId,
                packageId = original.packageId,
                kind = CardMovementKind.CORRECTION,
                effect = reversed,
                quantity = original.quantity,
                committedAtEpochMillis = now,
                customerSnapshot = original.customerSnapshot,
                packageSnapshot = original.packageSnapshot,
                reversesMovementId = original.id,
                correctionReason = request.reason,
                authorizedBy = request.authorizedBy
            )
        )

        central.singleOrNull()?.let { old ->
            val oldStock = db.centralStockDao()
                .currentBalance(old.packageId)

            val effect = if (
                old.effect == CentralStockEffect.INCREASE
            ) {
                CentralStockEffect.DECREASE
            } else {
                CentralStockEffect.INCREASE
            }

            val updated = if (
                effect == CentralStockEffect.INCREASE
            ) {
                Math.addExact(oldStock, old.quantity)
            } else {
                Math.subtractExact(oldStock, old.quantity)
            }

            require(updated >= 0L) {
                "Correction would overdraw central stock"
            }

            db.centralStockDao().insertMovement(
                CentralStockEntity(
                    id = newId(),
                    operationId = request.operationId,
                    packageId = old.packageId,
                    kind = CentralStockKind.CORRECTION,
                    effect = effect,
                    quantity = old.quantity,
                    balanceBefore = oldStock,
                    balanceAfter = updated,
                    committedAtEpochMillis = now,
                    packageSnapshot = old.packageSnapshot,
                    customerId = old.customerId,
                    reversesMovementId = old.id,
                    correctionReason = request.reason,
                    authorizedBy = request.authorizedBy
                )
            )
        }

        audit(
            request.operationId,
            AuditAction.CORRECTION,
            original.customerId,
            now,
            "Quantity reversal $ref",
            request.authorizedBy,
            original.id
        )

        result(
            request.operationId,
            ref,
            original.customerId,
            listOf(original.packageId)
        )
    }


    /** Append-only reversal of a standalone receipt. No existing row is updated. */
    override suspend fun reverseCollection(
        request: CollectionReversalRequest
    ): PostingReceipt = db.withTransaction {
        requireOperation(request.operationId, request.confirmed)
        val reason = request.reason.trim()
        require(reason.length in 5..500 && request.authorizedBy.isNotBlank()) {
            "يلزم سبب واضح وهوية مالك معتمدة"
        }
        val source = requireNotNull(db.financialDao().findById(request.originalEntryId)) {
            "إيصال التحصيل الأصلي غير موجود"
        }
        require(source.kind == FinancialEntryKind.COLLECTION &&
            source.settlementGroupId == null) {
            "تحصيل المحاسبة يعكس مع مجموعة المحاسبة كاملة، وليس منفردًا"
        }
        val description = "عكس تحصيل مستقل: $reason"
        if (priorOperation(request.operationId, AuditAction.CORRECTION)) {
            val prior = requireNotNull(db.auditDao().findById(request.operationId))
            val posted = db.financialDao().findByOperation(request.operationId)
                .singleOrNull()
            require(prior.correctionOfId == source.id &&
                prior.actorId == request.authorizedBy &&
                prior.description == description &&
                posted?.reversesEntryId == source.id &&
                posted.kind == FinancialEntryKind.COLLECTION_REVERSAL &&
                posted.amountMinor == source.amountMinor) {
                "معرف التصحيح مستخدم لعملية أخرى"
            }
            return@withTransaction result(request.operationId,
                "REV-${request.operationId}", source.customerId)
        }
        require(db.financialDao().findReversalOf(source.id) == null &&
            db.auditDao().findCorrectionOf(source.id) == null) {
            "تم عكس هذا التحصيل سابقًا"
        }
        val before = db.financialDao().currentDebt(source.customerId)
        require(before >= 0L) { "رصيد الدين غير سليم" }
        val after = Math.addExact(before, source.amountMinor)
        val now = System.currentTimeMillis()
        db.financialDao().insert(
            FinancialEntryEntity(
                id = newId(), operationId = request.operationId,
                customerId = source.customerId,
                kind = FinancialEntryKind.COLLECTION_REVERSAL,
                amountMinor = source.amountMinor,
                committedAtEpochMillis = now,
                customerSnapshot = source.customerSnapshot,
                reversesEntryId = source.id,
                correctionReason = reason,
                authorizedBy = request.authorizedBy
            )
        )
        require(db.financialDao().currentDebt(source.customerId) == after) {
            "فشل التحقق من قيد عكس التحصيل"
        }
        audit(request.operationId, AuditAction.CORRECTION,
            source.customerId, now, description, request.authorizedBy, source.id)
        result(request.operationId, "REV-${request.operationId}", source.customerId)
    }

    /** One atomic inverse of a completed settlement: sales, receivable and payment.
     * Original immutable settlement snapshots and archive memberships are retained.
     */
    override suspend fun reverseSettlement(
        request: SettlementReversalRequest
    ): PostingReceipt = db.withTransaction {
        requireOperation(request.operationId, request.confirmed)
        val reason = request.reason.trim()
        require(reason.length in 5..500 && request.authorizedBy.isNotBlank()) {
            "يلزم سبب واضح وهوية مالك معتمدة"
        }
        val group = requireNotNull(db.settlementDao().findGroup(request.settlementGroupId)) {
            "المحاسبة الأصلية غير موجودة"
        }
        val description = "عكس محاسبة كاملة: $reason"
        if (priorOperation(request.operationId, AuditAction.CORRECTION)) {
            val prior = requireNotNull(db.auditDao().findById(request.operationId))
            require(prior.correctionOfId == group.id &&
                prior.actorId == request.authorizedBy &&
                prior.description == description) {
                "معرف التصحيح مستخدم لعملية أخرى"
            }
            return@withTransaction result(request.operationId,
                "REV-${request.operationId}", group.customerId,
                db.settlementDao().findLines(group.id).map { it.packageId })
        }
        require(db.auditDao().findCorrectionOf(group.id) == null) {
            "هذه المحاسبة معكوسة بالفعل"
        }
        val lines = db.settlementDao().findLines(group.id)
        require(lines.isNotEmpty()) { "محاسبة بلا بنود" }
        for (line in lines) {
            require(!db.settlementDao().hasLaterPhysicalCount(
                group.customerId, line.packageId,
                group.committedAtEpochMillis, group.id
            )) {
                "توجد محاسبة لاحقة لهذه الباقة؛ اعكس أحدث محاسبة أولًا"
            }
        }
        val sales = db.cardMovementDao().findByOperation(group.operationId)
            .filter { it.kind == CardMovementKind.SETTLEMENT_SALE }
        val saleByLine = sales.associateBy { it.settlementLineId }
        require(sales.size == saleByLine.size &&
            sales.size == lines.count { it.soldQuantity > 0L } &&
            lines.all { line ->
                (line.soldQuantity == 0L && saleByLine[line.id] == null) ||
                    (line.soldQuantity > 0L && saleByLine[line.id]?.let {
                        it.customerId == group.customerId &&
                            it.packageId == line.packageId &&
                            it.quantity == line.soldQuantity &&
                            it.effect == ConsignmentEffect.DECREASE
                    } == true)
            }
        ) { "حركات بيع المحاسبة لا تطابق بنودها؛ لن يتم التصحيح" }
        val due = db.financialDao().findGroupEntry(group.id,
            FinancialEntryKind.SETTLEMENT_RECEIVABLE)
        val paid = db.financialDao().findGroupEntry(group.id,
            FinancialEntryKind.COLLECTION)
        val dueMatches = (group.totalAmountDueMinor == 0L && due == null) ||
            (due?.let { entry ->
                entry.amountMinor == group.totalAmountDueMinor &&
                    entry.customerId == group.customerId
            } == true)
        val paidMatches = (group.singlePaymentMinor == 0L && paid == null) ||
            (paid?.let { entry ->
                entry.amountMinor == group.singlePaymentMinor &&
                    entry.id == group.paymentEntryId &&
                    entry.customerId == group.customerId
            } == true)
        require(dueMatches && paidMatches) {
            "قيود المجموعة المالية لا تطابق المحاسبة الأصلية"
        }
        require(due == null || db.financialDao().findReversalOf(due.id) == null) {
            "المستحق الأصلي معكوس سابقًا"
        }
        require(paid == null || db.financialDao().findReversalOf(paid.id) == null) {
            "دفعة هذه المحاسبة معكوسة سابقًا؛ يلزم تسوية المراجعة أولًا"
        }
        val debtBefore = db.financialDao().currentDebt(group.customerId)
        val debtAfter = Math.addExact(
            Math.subtractExact(debtBefore, group.totalAmountDueMinor),
            group.singlePaymentMinor
        )
        require(debtAfter >= 0L) {
            "لا يمكن عكس المحاسبة: تحصيلات لاحقة تجعل الدين سالبًا. راجع التحصيلات أولًا"
        }
        require(sales.isNotEmpty() || due != null || paid != null) {
            "هذه المحاسبة لا تحتوي حركة مالية أو بيعًا لعكسها"
        }
        val beforeBalances = lines.associate { line ->
            line.packageId to db.cardMovementDao()
                .currentBalance(group.customerId, line.packageId)
        }
        lines.forEach { line ->
            require(beforeBalances.getValue(line.packageId) >= 0L)
            Math.addExact(beforeBalances.getValue(line.packageId), line.soldQuantity)
        }
        val now = System.currentTimeMillis()
        for (original in sales) {
            require(db.cardMovementDao().findById(original.id) != null)
            db.cardMovementDao().insert(
                CardMovementEntity(
                    id = newId(), operationId = request.operationId,
                    referenceNumber = reference("REVSALE"),
                    customerId = original.customerId,
                    packageId = original.packageId,
                    kind = CardMovementKind.CORRECTION,
                    effect = ConsignmentEffect.INCREASE,
                    quantity = original.quantity,
                    committedAtEpochMillis = now,
                    customerSnapshot = original.customerSnapshot,
                    packageSnapshot = original.packageSnapshot,
                    reversesMovementId = original.id,
                    correctionReason = reason,
                    authorizedBy = request.authorizedBy
                )
            )
        }
        due?.let { original ->
            db.financialDao().insert(
                FinancialEntryEntity(
                    id = newId(), operationId = request.operationId,
                    customerId = original.customerId,
                    kind = FinancialEntryKind.RECEIVABLE_REVERSAL,
                    amountMinor = original.amountMinor,
                    committedAtEpochMillis = now,
                    customerSnapshot = original.customerSnapshot,
                    reversesEntryId = original.id,
                    correctionReason = reason,
                    authorizedBy = request.authorizedBy
                )
            )
        }
        paid?.let { original ->
            db.financialDao().insert(
                FinancialEntryEntity(
                    id = newId(), operationId = request.operationId,
                    customerId = original.customerId,
                    kind = FinancialEntryKind.COLLECTION_REVERSAL,
                    amountMinor = original.amountMinor,
                    committedAtEpochMillis = now,
                    customerSnapshot = original.customerSnapshot,
                    reversesEntryId = original.id,
                    correctionReason = reason,
                    authorizedBy = request.authorizedBy
                )
            )
        }
        require(db.financialDao().currentDebt(group.customerId) == debtAfter) {
            "فشل تطابق الدين بعد العكس"
        }
        lines.forEach { line ->
            val expected = Math.addExact(beforeBalances.getValue(line.packageId), line.soldQuantity)
            require(db.cardMovementDao().currentBalance(group.customerId, line.packageId) == expected) {
                "فشل تطابق عهدة الباقة بعد العكس"
            }
        }
        audit(request.operationId, AuditAction.CORRECTION,
            group.customerId, now, description, request.authorizedBy, group.id)
        result(request.operationId, "REV-${request.operationId}",
            group.customerId, lines.map { it.packageId })
    }
}
