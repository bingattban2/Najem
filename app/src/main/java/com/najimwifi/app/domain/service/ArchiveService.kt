package com.najimwifi.app.domain.service

import androidx.room.withTransaction
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.entity.ArchiveBatchEntity
import com.najimwifi.app.data.local.entity.ArchiveMembershipEntity
import com.najimwifi.app.domain.model.ArchivedRecordType
import com.najimwifi.app.domain.model.CardMovementKind
import com.najimwifi.app.domain.model.ConsignmentEffect
import com.najimwifi.app.domain.model.FinancialEntryKind
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ArchiveEventType {
    SETTLEMENT,
    PAYMENT,
    DELIVERY,
    RETURN,
    CORRECTION
}

data class ArchiveCandidate(
    val groupId: String,
    val customerName: String,
    val committedAtEpochMillis: Long,
    val lineCount: Int,
    val amountDueMinor: Long,
    val paymentMinor: Long
)

data class ArchivePaymentCandidate(
    val entryId: String,
    val customerName: String,
    val committedAtEpochMillis: Long,
    val receiptNumber: String,
    val amountMinor: Long
)

data class ArchiveEventRow(
    val recordId: String,
    val groupId: String?,
    val batchId: String,
    val eventType: ArchiveEventType,
    val committedAtEpochMillis: Long,
    val customerName: String,
    val customerLocation: String,
    val packageName: String?,
    val eventQuantity: Long?,
    val remainingQuantity: Long?,
    val soldQuantity: Long?,
    val amountDueMinor: Long?,
    val paymentMinor: Long?,
    val containsPayment: Boolean = false
)

data class ArchiveLineDetails(
    val id: String,
    val packageName: String,
    val nominalValueMinor: Long,
    val settlementPriceMinor: Long,
    val stockBefore: Long,
    val stockRemaining: Long,
    val soldQuantity: Long,
    val amountDueMinor: Long
)

data class ArchiveGroupDetails(
    val groupId: String,
    val customerName: String,
    val committedAtEpochMillis: Long,
    val previousDebtMinor: Long,
    val totalAmountDueMinor: Long,
    val singlePaymentMinor: Long,
    val endingDebtMinor: Long,
    val lines: List<ArchiveLineDetails>
)

data class ArchiveRequest(
    val operationId: String,
    val confirmedBy: String,
    val groupIds: Set<String>,
    val paymentIds: Set<String> = emptySet()
)

data class ArchiveResult(
    val batchId: String,
    val recordCount: Int,
    val alreadyArchived: Boolean
)

/**
 * Archive metadata only.
 *
 * This service does not mutate settlement, financial, stock
 * or customer-ledger rows. Historical snapshots remain
 * authoritative and their original IDs are preserved.
 */
class ArchiveService(
    private val database: NajimDatabase
) {
    private val archives get() = database.archiveDao()

    suspend fun pendingGroups(): List<ArchiveCandidate> =
        archives.findNotYetArchivedGroups().map { group ->
            val lines = database.settlementDao()
                .findLines(group.id)

            ArchiveCandidate(
                groupId = group.id,
                customerName = group.customerSnapshot.name,
                committedAtEpochMillis =
                    group.committedAtEpochMillis,
                lineCount = lines.size,
                amountDueMinor = group.totalAmountDueMinor,
                paymentMinor = group.singlePaymentMinor
            )
        }

    suspend fun pendingPayments(): List<ArchivePaymentCandidate> =
        archives.findNotYetArchivedPayments().map { entry ->
            ArchivePaymentCandidate(
                entryId = entry.id,
                customerName = entry.customerSnapshot.name,
                committedAtEpochMillis = entry.committedAtEpochMillis,
                receiptNumber = requireNotNull(entry.receiptNumber),
                amountMinor = entry.amountMinor
            )
        }

    /**
     * Posting only completed settlement groups and their
     * confirmed associated sale movements/payment reference.
     *
     * Unsettled deliveries are deliberately excluded.
     * A membership unique key blocks repeat archiving.
     */
    suspend fun archive(
        request: ArchiveRequest
    ): ArchiveResult = database.withTransaction {

        require(request.operationId.isNotBlank()) {
            "معرف طلب الأرشفة مطلوب"
        }

        val actor = request.confirmedBy.trim()

        require(actor.isNotEmpty()) {
            "أدخل اسم منفذ الأرشفة"
        }

        require(request.groupIds.isNotEmpty() || request.paymentIds.isNotEmpty()) {
            "حدد محاسبة مكتملة أو تحصيلًا مستقلًا"
        }

        archives.findBatchByOperation(
            request.operationId
        )?.let { existing ->
            val archivedGroups = archives
                .findMemberships(existing.id)
                .filter {
                    it.recordType ==
                        ArchivedRecordType.SETTLEMENT_GROUP
                }
                .map { it.recordId }
                .toSet()

            val archivedIndependentPayments = archives.findMemberships(existing.id)
                .filter { it.recordType == ArchivedRecordType.FINANCIAL_ENTRY }
                .mapNotNull { database.financialDao().findById(it.recordId) }
                .filter { it.settlementGroupId == null }
                .map { it.id }.toSet()
            require(
                archivedGroups == request.groupIds &&
                    archivedIndependentPayments == request.paymentIds &&
                    existing.confirmedBy == actor
            ) {
                "معرف العملية استُخدم لطلب أرشفة مختلف"
            }

            return@withTransaction ArchiveResult(
                batchId = existing.id,
                recordCount = existing.recordCount,
                alreadyArchived = true
            )
        }

        val available = archives
            .findNotYetArchivedGroups()
            .associateBy { it.id }

        require(request.groupIds.all { it in available }) {
            "بعض المحاسبات أُرشفت بالفعل أو لم تعد مؤهلة"
        }
        val availablePayments = archives.findNotYetArchivedPayments().associateBy { it.id }
        require(request.paymentIds.all { it in availablePayments }) {
            "بعض التحصيلات أُرشفت بالفعل أو لم تعد مؤهلة"
        }

        val batchId = UUID.randomUUID().toString()

        val memberships = LinkedHashMap<
            Pair<ArchivedRecordType, String>,
            ArchiveMembershipEntity
        >()

        fun add(
            type: ArchivedRecordType,
            recordId: String
        ) {
            val key = type to recordId

            memberships[key] = ArchiveMembershipEntity(
                recordType = type,
                recordId = recordId,
                archiveBatchId = batchId
            )
        }

        request.groupIds.sorted().forEach { groupId ->
            val group = requireNotNull(available[groupId])

            val lines = database.settlementDao()
                .findLines(group.id)

            require(lines.isNotEmpty()) {
                "مجموعة محاسبة بلا بنود؛ لا يمكن أرشفتها"
            }

            val lineIds = lines.map { it.id }.toSet()

            add(
                ArchivedRecordType.SETTLEMENT_GROUP,
                group.id
            )

            // Associated sale postings are kept by ID.
            // They are not duplicated into the archive ledger.
            database.cardMovementDao()
                .findByOperation(group.operationId)
                .filter {
                    it.kind == CardMovementKind.SETTLEMENT_SALE &&
                        it.settlementLineId in lineIds
                }
                .forEach {
                    add(
                        ArchivedRecordType.CARD_MOVEMENT,
                        it.id
                    )
                }

            // Include the historical additions for settled packages. Archive
            // memberships do not touch current customer quantities, even if
            // the most recent settlement left unsold cards with the customer.
            lines.map { it.packageId }.distinct().forEach { packageId ->
                archives.findUnarchivedDeliveries(
                    group.customerId, packageId, group.committedAtEpochMillis
                ).forEach { movement ->
                    add(ArchivedRecordType.CARD_MOVEMENT, movement.id)
                }
            }

            // A settlement has at most one associated payment.
            group.paymentEntryId?.let { paymentId ->
                add(
                    ArchivedRecordType.FINANCIAL_ENTRY,
                    paymentId
                )
            }
        }

        request.paymentIds.sorted().forEach { entryId ->
            val entry = requireNotNull(availablePayments[entryId])
            require(entry.kind == FinancialEntryKind.COLLECTION && entry.settlementGroupId == null)
            add(ArchivedRecordType.FINANCIAL_ENTRY, entry.id)
        }

        require(memberships.isNotEmpty()) {
            "لا توجد عمليات مؤهلة للأرشفة"
        }

        val now = System.currentTimeMillis()

        archives.insertBatch(
            ArchiveBatchEntity(
                id = batchId,
                operationId = request.operationId,
                archivedAtEpochMillis = now,
                confirmedBy = actor,
                recordCount = memberships.size
            )
        )

        archives.insertMemberships(
            memberships.values.toList()
        )

        ArchiveResult(
            batchId = batchId,
            recordCount = memberships.size,
            alreadyArchived = false
        )
    }

    /**
     * Re-emits when a new archive batch is committed.
     * Reads the original immutable operations by reference.
     */
    fun observeArchivedEvents():
        Flow<List<ArchiveEventRow>> =
        archives.observeBatches().map { batches ->
            buildList {
                batches.forEach { batch ->
                    val members = archives
                        .findMemberships(batch.id)

                    members.forEach { membership ->
                        when (membership.recordType) {
                            ArchivedRecordType.SETTLEMENT_GROUP -> {
                                val group = database.settlementDao()
                                    .findGroup(membership.recordId)
                                    ?: return@forEach

                                val lines = database.settlementDao()
                                    .findLines(group.id)

                                val sold = lines.fold(0L) { sum, line ->
                                    Math.addExact(
                                        sum,
                                        line.soldQuantity
                                    )
                                }

                                val before = lines.fold(0L) { total, line ->
                                    Math.addExact(total, line.stockBefore)
                                }
                                val remaining = lines.fold(0L) { total, line ->
                                    Math.addExact(total, line.stockRemaining)
                                }

                                add(
                                    ArchiveEventRow(
                                        recordId = group.id,
                                        groupId = group.id,
                                        batchId = batch.id,
                                        eventType =
                                            ArchiveEventType.SETTLEMENT,
                                        committedAtEpochMillis =
                                            group.committedAtEpochMillis,
                                        customerName =
                                            group.customerSnapshot.name,
                                        customerLocation =
                                            group.customerSnapshot.location,
                                        packageName =
                                            if (lines.size == 1)
                                                lines.first()
                                                    .packageSnapshot.name
                                            else
                                                "جميع الفئات (${lines.size})",
                                        eventQuantity = before,
                                        remainingQuantity = remaining,
                                        soldQuantity = sold,
                                        amountDueMinor =
                                            group.totalAmountDueMinor,
                                        paymentMinor =
                                            group.singlePaymentMinor,
                                        containsPayment =
                                            group.singlePaymentMinor > 0L
                                    )
                                )
                            }

                            ArchivedRecordType.CARD_MOVEMENT -> {
                                val movement = database.cardMovementDao()
                                    .findById(membership.recordId)
                                    ?: return@forEach

                                val type = when (movement.kind) {
                                    CardMovementKind.DELIVERY ->
                                        ArchiveEventType.DELIVERY

                                    CardMovementKind.UNSOLD_RETURN ->
                                        ArchiveEventType.RETURN

                                    CardMovementKind.CORRECTION ->
                                        ArchiveEventType.CORRECTION

                                    // The settlement group already
                                    // represents this sale once.
                                    CardMovementKind.SETTLEMENT_SALE ->
                                        null
                                }

                                if (type != null) {
                                    val signedQuantity =
                                        if (
                                            movement.effect ==
                                            ConsignmentEffect.INCREASE
                                        ) {
                                            movement.quantity
                                        } else {
                                            -movement.quantity
                                        }

                                    add(
                                        ArchiveEventRow(
                                            recordId = movement.id,
                                            groupId = null,
                                            batchId = batch.id,
                                            eventType = type,
                                            committedAtEpochMillis =
                                                movement.committedAtEpochMillis,
                                            customerName =
                                                movement.customerSnapshot.name,
                                            customerLocation =
                                                movement.customerSnapshot.location,
                                            packageName =
                                                movement.packageSnapshot.name,
                                            eventQuantity = signedQuantity,
                                            remainingQuantity = null,
                                            soldQuantity = null,
                                            amountDueMinor = null,
                                            paymentMinor = null
                                        )
                                    )
                                }
                            }

                            ArchivedRecordType.FINANCIAL_ENTRY -> {
                                val entry = database.financialDao()
                                    .findById(membership.recordId) ?: return@forEach
                                // One payment already appears with its settlement group.
                                if (entry.settlementGroupId == null &&
                                    entry.kind == FinancialEntryKind.COLLECTION) {
                                    add(ArchiveEventRow(
                                        recordId = entry.id,
                                        groupId = null,
                                        batchId = batch.id,
                                        eventType = ArchiveEventType.PAYMENT,
                                        committedAtEpochMillis = entry.committedAtEpochMillis,
                                        customerName = entry.customerSnapshot.name,
                                        customerLocation = entry.customerSnapshot.location,
                                        packageName = null,
                                        eventQuantity = null,
                                        remainingQuantity = null,
                                        soldQuantity = null,
                                        amountDueMinor = null,
                                        paymentMinor = entry.amountMinor,
                                        containsPayment = true
                                    ))
                                }
                            }
                        }
                    }
                }
            }.sortedWith(
                compareByDescending<ArchiveEventRow> {
                    it.committedAtEpochMillis
                }.thenByDescending { it.recordId }
            )
        }

    suspend fun groupDetails(
        groupId: String
    ): ArchiveGroupDetails {
        require(groupId.isNotBlank())

        require(
            archives.findArchivedGroup(groupId) != null
        ) {
            "هذه المحاسبة ليست ضمن الأرشيف"
        }

        val group = requireNotNull(
            database.settlementDao().findGroup(groupId)
        ) {
            "مجموعة المحاسبة غير موجودة"
        }

        val lines = database.settlementDao()
            .findLines(groupId)

        return ArchiveGroupDetails(
            groupId = group.id,
            customerName = group.customerSnapshot.name,
            committedAtEpochMillis =
                group.committedAtEpochMillis,
            previousDebtMinor = group.previousDebtMinor,
            totalAmountDueMinor =
                group.totalAmountDueMinor,
            singlePaymentMinor = group.singlePaymentMinor,
            endingDebtMinor = group.endingDebtMinor,
            lines = lines.map { line ->
                ArchiveLineDetails(
                    id = line.id,
                    packageName =
                        line.packageSnapshot.name,
                    nominalValueMinor =
                        line.packageSnapshot.nominalValueMinor,
                    settlementPriceMinor =
                        line.packageSnapshot
                            .generalSettlementPriceMinor,
                    stockBefore = line.stockBefore,
                    stockRemaining = line.stockRemaining,
                    soldQuantity = line.soldQuantity,
                    amountDueMinor = line.amountDueMinor
                )
            }
        )
    }
}