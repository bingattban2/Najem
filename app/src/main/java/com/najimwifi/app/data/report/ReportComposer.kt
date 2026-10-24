package com.najimwifi.app.data.report

import com.najimwifi.app.domain.report.ReportCustomerTotals
import com.najimwifi.app.domain.report.ReportDocument
import com.najimwifi.app.domain.report.ReportEventType
import com.najimwifi.app.domain.report.ReportRequest
import com.najimwifi.app.domain.report.ReportRow
import com.najimwifi.app.domain.report.ReportScope
import com.najimwifi.app.domain.report.ReportTotals

/** All totals use checked integer arithmetic and original commit snapshots. */
class ReportComposer(private val queries: ReportQueries) {

    suspend fun compose(request: ReportRequest): ReportDocument =
        fromSnapshot(request, queries.snapshot())

    internal fun fromSnapshot(
        request: ReportRequest,
        source: ReportSource
    ): ReportDocument {
        fun sum(a: Long, b: Long) = Math.addExact(a, b)
        fun stockEffect(row: ReportDbRow): Long {
            val quantity = row.requiredNumber("quantity")
            require(quantity > 0L) { "كمية حركة كروت غير صحيحة" }
            return when (row.requiredText("effect")) {
                "INCREASE" -> quantity
                "DECREASE" -> Math.negateExact(quantity)
                else -> error("تأثير حركة عهدة غير معروف")
            }
        }

        fun customerName(row: ReportDbRow): String =
            row.requiredText("customer_name")
        fun customerLocation(row: ReportDbRow): String =
            row.text("customer_location").orEmpty()
        fun packageName(row: ReportDbRow): String =
            row.requiredText("package_name")
        fun nominal(row: ReportDbRow): Long =
            row.requiredNumber("package_nominalValueMinor")
        fun price(row: ReportDbRow): Long =
            row.requiredNumber("package_generalSettlementPriceMinor")

        val groups = source.groups.associateBy { it.requiredText("id") }
        val lineGroups = source.lines.groupBy { it.requiredText("settlementGroupId") }
        val archivedGroups = source.archiveMemberships.filter {
            it.text("recordType") == "SETTLEMENT_GROUP"
        }.map { it.requiredText("recordId") }.toSet()
        val archivedMovements = source.archiveMemberships.filter {
            it.text("recordType") == "CARD_MOVEMENT"
        }.map { it.requiredText("recordId") }.toSet()
        val archivedFinancial = source.archiveMemberships.filter {
            it.text("recordType") == "FINANCIAL_ENTRY"
        }.map { it.requiredText("recordId") }.toSet()

        // Every group is checked in full before applying a package filter.
        groups.forEach { (groupId, group) ->
            val lines = lineGroups[groupId].orEmpty()
            require(lines.isNotEmpty()) { "مجموعة تسوية بلا بنود: $groupId" }
            val due = lines.fold(0L) { acc, line ->
                val sold = line.requiredNumber("soldQuantity")
                val expected = Math.multiplyExact(sold, price(line))
                val recorded = line.requiredNumber("amountDueMinor")
                require(expected == recorded) { "مبلغ تسوية لا يطابق السعر التاريخي" }
                sum(acc, recorded)
            }
            require(due == group.requiredNumber("totalAmountDueMinor")) {
                "إجمالي مجموعة التسوية لا يطابق بنودها"
            }
        }

        val namedCustomer = source.customers.associateBy { it.requiredText("id") }
        val selectedCustomers = if (request.customerId != null) {
            setOf(request.customerId)
        } else {
            namedCustomer.keys + groups.values.map {
                it.requiredText("customerId")
            } + source.movements.map { it.requiredText("customerId") } +
                source.financial.map { it.requiredText("customerId") }
        }
        val filteredCustomers = if (request.customerSearch.isBlank()) {
            selectedCustomers
        } else {
            val term = request.customerSearch.trim()
            selectedCustomers.filter { id ->
                namedCustomer[id]?.text("name")?.contains(term, true) == true ||
                    source.groups.any {
                        it.text("customerId") == id &&
                            customerName(it).contains(term, true)
                    } || source.movements.any {
                        it.text("customerId") == id &&
                            customerName(it).contains(term, true)
                    } || source.financial.any {
                        it.text("customerId") == id &&
                            customerName(it).contains(term, true)
                    }
            }.toSet()
        }
        val movementScope = source.movements.filter { row ->
            row.requiredText("customerId") in filteredCustomers
        }
        val groupScope = groups.values.filter { row ->
            row.requiredText("customerId") in filteredCustomers
        }
        val financialScope = source.financial.filter { row ->
            row.requiredText("customerId") in filteredCustomers
        }

        val associatedPaymentIds = groups.values.mapNotNull {
            it.text("paymentEntryId")
        }.toSet()
        val allGroupIds = groups.keys

        // A reversal is an additional, immutable journal row. Never rewrite
        // the source amount, and show "reversed" only after the reversal date.
        val financialById = source.financial.associateBy { it.requiredText("id") }
        val reversalByOriginal = source.financial.filter {
            it.requiredText("kind") in setOf(
                "COLLECTION_REVERSAL", "RECEIVABLE_REVERSAL"
            )
        }.associateBy { reversal ->
            val originalId = reversal.requiredText("reversesEntryId")
            val original = requireNotNull(financialById[originalId]) {
                "قيد عكسي لا يرتبط بقيد أصلي: $originalId"
            }
            val expectedOriginalKind = when (reversal.requiredText("kind")) {
                "COLLECTION_REVERSAL" -> "COLLECTION"
                else -> "SETTLEMENT_RECEIVABLE"
            }
            require(original.requiredText("kind") == expectedOriginalKind &&
                original.requiredText("customerId") == reversal.requiredText("customerId") &&
                original.requiredNumber("amountMinor") == reversal.requiredNumber("amountMinor") &&
                original.requiredNumber("committedAtEpochMillis") <=
                reversal.requiredNumber("committedAtEpochMillis")) {
                "قيد عكسي غير مطابق للقيد الأصلي: $originalId"
            }
            originalId
        }
        require(reversalByOriginal.size == source.financial.count {
            it.requiredText("kind") in setOf(
                "COLLECTION_REVERSAL", "RECEIVABLE_REVERSAL"
            )
        }) { "أكثر من عكس للقيد المالي نفسه" }

        fun reversedAt(originalId: String?): Long? = originalId
            ?.let(reversalByOriginal::get)
            ?.requiredNumber("committedAtEpochMillis")
            ?.takeIf(request::isAtOrBeforeEnd)

        val movementById = source.movements.associateBy { it.requiredText("id") }
        val movementReversalByOriginal = source.movements.filter {
            it.requiredText("kind") == "CORRECTION"
        }.mapNotNull { reversal ->
            reversal.text("reversesMovementId")?.let { it to reversal }
        }.toMap()
        fun movementReversedAt(originalId: String): Long? =
            movementReversalByOriginal[originalId]
                ?.requiredNumber("committedAtEpochMillis")
                ?.takeIf(request::isAtOrBeforeEnd)
        fun groupReversedAt(group: ReportDbRow): Long? {
            val groupId = group.requiredText("id")
            val receivable = source.financial.firstOrNull {
                it.text("settlementGroupId") == groupId &&
                    it.text("kind") == "SETTLEMENT_RECEIVABLE"
            }
            return listOfNotNull(
                reversedAt(receivable?.text("id")),
                reversedAt(group.text("paymentEntryId"))
            ).minOrNull()
        }

        val ledger = financialScope.map { row ->
            val time = row.requiredNumber("committedAtEpochMillis")
            val delta = financialDelta(row)
            Triple(row, time, delta)
        }
        val rows = ArrayList<ReportRow>()

        for (movement in movementScope) {
            val kind = movement.requiredText("kind")
            if (kind == "SETTLEMENT_SALE") continue // represented by its line
            val type = when (kind) {
                "DELIVERY" -> ReportEventType.DELIVERY
                "UNSOLD_RETURN" -> ReportEventType.RETURN
                "CORRECTION" -> ReportEventType.CORRECTION
                else -> error("نوع حركة غير معروف في التقرير: $kind")
            }
            val id = movement.requiredText("id")
            val date = movement.requiredNumber("committedAtEpochMillis")
            if (!request.includesInstant(date) || type !in request.eventTypes) continue
            if (request.scope == ReportScope.ARCHIVE && id !in archivedMovements) continue
            val packageId = movement.requiredText("packageId")
            if (request.packageId != null && request.packageId != packageId) continue
            val label = customerName(movement)
            if (!label.contains(request.customerSearch.trim(), true)) continue
            if (!packageName(movement).contains(request.packageSearch.trim(), true)) continue
            val quantity = movement.requiredNumber("quantity")
            val correctedSourceId = movement.text("reversesMovementId")
            val correctedSource = correctedSourceId?.let(movementById::get)
            val correctionKind = if (type == ReportEventType.CORRECTION) {
                when (correctedSource?.text("kind")) {
                    "DELIVERY" -> "عكس تسليم"
                    "UNSOLD_RETURN" -> "عكس إرجاع"
                    "SETTLEMENT_SALE" -> "عكس بيع محاسبة"
                    else -> "تصحيح عهدة"
                }
            } else null
            rows += ReportRow(
                id = id, customerId = movement.requiredText("customerId"),
                customerNameAtEvent = label,
                customerLocationAtEvent = customerLocation(movement),
                packageId = packageId, packageNameAtEvent = packageName(movement),
                committedAtEpochMillis = date, type = type,
                nominalValueMinor = nominal(movement), globalPriceMinor = price(movement),
                deliveredQuantity = if (type == ReportEventType.DELIVERY) quantity else null,
                returnedQuantity = if (type == ReportEventType.RETURN) quantity else null,
                referenceNumber = movement.text("referenceNumber"),
                reversedAtEpochMillis = movementReversedAt(id),
                reversesRecordId = correctedSourceId,
                correctionKind = correctionKind,
                correctionReason = movement.text("correctionReason"),
                authorizedBy = movement.text("authorizedBy"),
                consignmentDeltaQuantity = if (type == ReportEventType.CORRECTION)
                    stockEffect(movement) else null
            )
        }

        for (group in groupScope) {
            val groupId = group.requiredText("id")
            val date = group.requiredNumber("committedAtEpochMillis")
            if (!request.includesInstant(date)) continue
            if (request.scope == ReportScope.ARCHIVE && groupId !in archivedGroups) continue
            val label = customerName(group)
            if (!label.contains(request.customerSearch.trim(), true)) continue
            if (ReportEventType.SETTLEMENT in request.eventTypes) {
                for (line in lineGroups[groupId].orEmpty()) {
                    val packageId = line.requiredText("packageId")
                    if (request.packageId != null && request.packageId != packageId) continue
                    if (!packageName(line).contains(request.packageSearch.trim(), true)) continue
                    rows += ReportRow(
                        id = line.requiredText("id"), groupId = groupId,
                        customerId = group.requiredText("customerId"),
                        customerNameAtEvent = label,
                        customerLocationAtEvent = customerLocation(group),
                        packageId = packageId, packageNameAtEvent = packageName(line),
                        committedAtEpochMillis = date, type = ReportEventType.SETTLEMENT,
                        nominalValueMinor = nominal(line), globalPriceMinor = price(line),
                        remainingQuantity = line.requiredNumber("stockRemaining"),
                        soldQuantity = line.requiredNumber("soldQuantity"),
                        amountDueMinor = line.requiredNumber("amountDueMinor"),
                        referenceNumber = group.text("referenceNumber"),
                        reversedAtEpochMillis = groupReversedAt(group)
                    )
                }
            }
            // The group's one payment is NOT repeated under each package.
            val payment = group.requiredNumber("singlePaymentMinor")
            // A group payment belongs to the entire settlement, not one package.
            // Never attribute the full payment to a package-filtered report.
            val reportCoversAllPackages =
                request.packageId == null && request.packageSearch.isBlank()
            if (payment > 0L && reportCoversAllPackages &&
                ReportEventType.PAYMENT in request.eventTypes) {
                val financialPayment = financialScope.firstOrNull {
                    it.text("id") == group.text("paymentEntryId")
                }
                rows += ReportRow(
                    id = "group-payment:$groupId", groupId = groupId,
                    customerId = group.requiredText("customerId"),
                    customerNameAtEvent = label,
                    customerLocationAtEvent = customerLocation(group),
                    committedAtEpochMillis = date, type = ReportEventType.PAYMENT,
                    paymentMinor = payment,
                    paymentMethod = financialPayment?.text("paymentMethod"),
                    receiptNumber = financialPayment?.text("receiptNumber"),
                    referenceNumber = group.text("referenceNumber"),
                    reversedAtEpochMillis = reversedAt(group.text("paymentEntryId"))
                )
            }
        }

        // Collections and reversals are distinct financial events.
        // A reversal is NEVER mislabeled as a new customer collection.
        for ((entry, date, delta) in ledger) {
            val kind = entry.requiredText("kind")
            if (kind != "COLLECTION" &&
                kind != "RECEIVABLE_REVERSAL" &&
                kind != "COLLECTION_REVERSAL") continue
            val id = entry.requiredText("id")
            val linkedGroup = entry.text("settlementGroupId")
            if (kind == "COLLECTION" &&
                (id in associatedPaymentIds || linkedGroup in allGroupIds)) continue
            if (!request.includesInstant(date)) continue
            val type = if (kind == "COLLECTION")
                ReportEventType.PAYMENT else ReportEventType.CORRECTION
            if (type !in request.eventTypes) continue
            if (request.scope == ReportScope.ARCHIVE && id !in archivedFinancial) continue
            // Independent payments/reversals do not have a package ID either.
            // Keep them in customer-wide history, not a specific package ledger.
            if (request.packageId != null || request.packageSearch.isNotBlank()) continue
            val label = customerName(entry)
            if (!label.contains(request.customerSearch.trim(), true)) continue
            rows += ReportRow(
                id = id, customerId = entry.requiredText("customerId"),
                customerNameAtEvent = label,
                customerLocationAtEvent = customerLocation(entry),
                committedAtEpochMillis = date, type = type,
                paymentMinor = if (kind == "COLLECTION") Math.negateExact(delta) else null,
                adjustmentMinor = if (kind != "COLLECTION") delta else null,
                paymentMethod = entry.text("paymentMethod"),
                receiptNumber = entry.text("receiptNumber"),
                referenceNumber = entry.text("referenceNumber"),
                reversedAtEpochMillis = if (kind == "COLLECTION") reversedAt(id) else null,
                reversesRecordId = entry.text("reversesEntryId"),
                correctionKind = when (kind) {
                    "COLLECTION_REVERSAL" -> "عكس تحصيل"
                    "RECEIVABLE_REVERSAL" -> "عكس مستحق محاسبة"
                    else -> null
                },
                correctionReason = entry.text("correctionReason"),
                authorizedBy = entry.text("authorizedBy")
            )
        }

        // Period reports have global customer debt: never allocate old debt
        // to one package or count group payments once per package.
        val summaries = filteredCustomers.sorted().map { id ->
            val entries = ledger.filter { it.first.requiredText("customerId") == id }
            val opening = entries.filter { request.isBeforePeriod(it.second) }
                .fold(0L) { acc, item -> sum(acc, item.third) }
            val closing = entries.filter { request.isAtOrBeforeEnd(it.second) }
                .fold(0L) { acc, item -> sum(acc, item.third) }
            require(opening >= 0 && closing >= 0) { "تعارض في القيود المالية التاريخية للعميل $id" }

            val groupPeriod = groupScope.filter {
                it.requiredText("customerId") == id &&
                    request.includesInstant(it.requiredNumber("committedAtEpochMillis"))
            }
            val globalNewSales = groupPeriod.fold(0L) { acc, group ->
                sum(acc, group.requiredNumber("totalAmountDueMinor"))
            }
            val receipts = entries.filter {
                request.includesInstant(it.second) &&
                    it.first.requiredText("kind") == "COLLECTION"
            }.fold(0L) { acc, item -> sum(acc, Math.negateExact(item.third)) }
            val newReceivables = entries.filter {
                request.includesInstant(it.second) &&
                    it.first.requiredText("kind") == "SETTLEMENT_RECEIVABLE"
            }.fold(0L) { acc, item -> sum(acc, item.third) }
            val adjustments = entries.filter {
                request.includesInstant(it.second) &&
                    it.first.requiredText("kind") in setOf(
                        "RECEIVABLE_REVERSAL", "COLLECTION_REVERSAL"
                    )
            }.fold(0L) { acc, item -> sum(acc, item.third) }
            require(sum(sum(sum(opening, newReceivables), -receipts), adjustments) == closing) {
                "قيود العميل لا تتطابق مع رصيد نهاية الفترة"
            }
            require(globalNewSales == newReceivables) {
                "مبالغ التسوية لا تتطابق مع قيود الذمم للعميل $id"
            }
            val reversedCollections = entries.filter {
                request.includesInstant(it.second) &&
                    it.first.requiredText("kind") == "COLLECTION_REVERSAL"
            }.fold(0L) { acc, item -> sum(acc, item.third) }
            val reversedReceivables = entries.filter {
                request.includesInstant(it.second) &&
                    it.first.requiredText("kind") == "RECEIVABLE_REVERSAL"
            }.fold(0L) { acc, item -> sum(acc, Math.negateExact(item.third)) }
            // Visible sales group amounts are validated separately from the ledger.
            val periodMovements = movementScope.filter {
                it.requiredText("customerId") == id &&
                    request.includesInstant(it.requiredNumber("committedAtEpochMillis")) &&
                    (request.packageId == null || it.requiredText("packageId") == request.packageId)
            }
            fun movementCount(kind: String): Long = periodMovements.filter {
                it.requiredText("kind") == kind
            }.fold(0L) { acc, item -> sum(acc, item.requiredNumber("quantity")) }
            val sales = groupPeriod.flatMap { lineGroups[it.requiredText("id")].orEmpty() }
                .filter { request.packageId == null || it.requiredText("packageId") == request.packageId }
                .fold(0L) { acc, item -> sum(acc, item.requiredNumber("soldQuantity")) }
            val closingCards = movementScope.filter {
                it.requiredText("customerId") == id &&
                    request.isAtOrBeforeEnd(it.requiredNumber("committedAtEpochMillis")) &&
                    (request.packageId == null || it.requiredText("packageId") == request.packageId)
            }.fold(0L) { acc, movement -> sum(acc, stockEffect(movement)) }
            require(closingCards >= 0L) { "رصيد كروت سالب للعميل $id" }
            ReportCustomerTotals(
                customerId = id,
                customerLabel = namedCustomer[id]?.text("name")
                    ?: groupScope.firstOrNull { it.text("customerId") == id }
                        ?.let(::customerName) ?: id,
                openingDebtMinor = opening,
                newSalesDueMinor = globalNewSales,
                collectionsMinor = receipts,
                adjustmentsMinor = adjustments,
                closingDebtMinor = closing,
                deliveredCards = movementCount("DELIVERY"),
                soldCards = sales,
                returnedCards = movementCount("UNSOLD_RETURN"),
                closingCards = closingCards,
                reversedCollectionsMinor = reversedCollections,
                reversedReceivablesMinor = reversedReceivables
            )
        }

        val chronological = compareBy<ReportRow> { it.committedAtEpochMillis }
            .thenBy { it.groupId ?: it.id }.thenBy { it.id }
        val visible = rows.sortedWith(
            if (request.newestFirst) chronological.reversed() else chronological
        )
        fun total(selector: (ReportCustomerTotals) -> Long): Long =
            summaries.fold(0L) { acc, item -> sum(acc, selector(item)) }
        fun rowTotal(selector: (ReportRow) -> Long?): Long =
            visible.fold(0L) { acc, item -> sum(acc, selector(item) ?: 0L) }

        // The simplified application exposes due amounts from settlement lines only.
        // Historical legacy financial entries remain intact for backup compatibility
        // and consistency verification; they are not a customer debt dashboard.
        val notes = listOf("المبلغ المستحق لكل محاسبة = عدد الكروت المباعة × قيمة الباقة وقت العملية")
        return ReportDocument(
            request = request,
            title = when (request.scope) {
                ReportScope.CUSTOMER -> "كشف حركات العميل"
                ReportScope.DEBTS -> "كشف المبالغ المستحقة"
                ReportScope.ARCHIVE -> "تقرير الأرشيف"
            },
            generatedAtEpochMillis = request.generatedAtEpochMillis,
            rows = visible,
            customers = summaries,
            totals = ReportTotals(
                deliveredCards = rowTotal { it.deliveredQuantity },
                soldCards = rowTotal { it.soldQuantity },
                returnedCards = rowTotal { it.returnedQuantity },
                salesDueMinor = rowTotal { it.amountDueMinor },
                receiptsMinor = rowTotal { it.paymentMinor },
                adjustmentsMinor = rowTotal { it.adjustmentMinor },
                closingCards = total { it.closingCards },
                openingDebtMinor = total { it.openingDebtMinor },
                closingDebtMinor = total { it.closingDebtMinor },
                reversedCollectionsMinor = total { it.reversedCollectionsMinor },
                reversedReceivablesMinor = total { it.reversedReceivablesMinor }
            ),
            notes = notes
        )
    }

    private fun financialDelta(row: ReportDbRow): Long {
        // These are the exact four kinds used by FinancialDao.currentDebt.
        val kind = row.requiredText("kind")
        val amount = row.requiredNumber("amountMinor")
        require(amount >= 0L) { "قيمة مالية سالبة غير صالحة" }
        return when (kind) {
            "SETTLEMENT_RECEIVABLE", "COLLECTION_REVERSAL" -> amount
            "COLLECTION", "RECEIVABLE_REVERSAL" -> Math.negateExact(amount)
            else -> error("نوع قيد مالي غير معروف في التقرير: $kind")
        }
    }
}
