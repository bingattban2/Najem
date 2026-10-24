package com.najimwifi.app.data.repository

import androidx.room.withTransaction
import com.najimwifi.app.data.local.entity.AuditEventEntity
import com.najimwifi.app.domain.model.AuditAction
import com.najimwifi.app.domain.model.AuditOutcome
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.entity.CustomerEntity
import com.najimwifi.app.data.local.entity.PackageEntity
import com.najimwifi.app.domain.contract.CatalogRepository
import com.najimwifi.app.domain.contract.CustomerDraft
import com.najimwifi.app.domain.contract.PackageDraft
import com.najimwifi.app.domain.contract.RemovalResult
import com.najimwifi.app.domain.model.Customer
import com.najimwifi.app.domain.model.Package
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Catalog writes never update historical financial or card snapshots. */
class CatalogRepositoryImpl(
    private val database: NajimDatabase
) : CatalogRepository {

    private val spaces = Regex("\\s+")
    private val validPhone = Regex("^\\+?[0-9]{6,15}$")

    private fun clean(value: String): String =
        value.trim().replace(spaces, " ")

    private fun normalName(value: String): String =
        Normalizer.normalize(
            clean(value),
            Normalizer.Form.NFKC
        ).lowercase(Locale.ROOT)

    private fun phone(value: String?): String? {
        val compact = value?.trim()
            ?.replace(" ", "")
            ?.replace("-", "")

        if (compact.isNullOrEmpty()) return null

        require(validPhone.matches(compact)) {
            "رقم الهاتف غير صحيح"
        }
        return compact
    }

    private suspend fun recordChange(action: AuditAction, id: String, title: String) {
        val operation = UUID.randomUUID().toString()
        database.auditDao().insert(AuditEventEntity(
            id = operation, operationId = operation,
            occurredAtEpochMillis = System.currentTimeMillis(),
            action = action, outcome = AuditOutcome.COMMITTED,
            subjectId = id, description = title
        ))
    }

    private fun nextTime(previous: Long): Long =
        maxOf(
            System.currentTimeMillis(),
            Math.addExact(previous, 1L)
        )

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
        generalSettlementPriceMinor = generalSettlementPriceMinor,
        colorArgb = colorArgb,
        isActive = isActive,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis
    )

    override fun observeCustomers(): Flow<List<Customer>> =
        database.customerDao().observeAll().map { rows ->
            rows.map { it.toDomain() }
        }

    override fun observePackages(): Flow<List<Package>> =
        database.packageDao().observeAll().map { rows ->
            rows.map { it.toDomain() }
        }

    override suspend fun saveCustomer(
        draft: CustomerDraft,
        customerId: String?,
        expectedUpdatedAtEpochMillis: Long?
    ): String = database.withTransaction {

        val name = clean(draft.name)
        val location = clean(draft.location)

        require(name.isNotEmpty() && location.isNotEmpty()) {
            "اسم العميل والموقع مطلوبان"
        }

        val number = phone(draft.phone)
        val dao = database.customerDao()

        if (customerId == null) {
            require(expectedUpdatedAtEpochMillis == null)

            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            dao.insert(
                CustomerEntity(
                    id = id,
                    name = name,
                    location = location,
                    phone = number,
                    createdAtEpochMillis = now
                )
            )
            recordChange(AuditAction.CUSTOMER_CHANGE, id, "إضافة العميل: $name")
            id
        } else {
            val current = requireNotNull(
                dao.findById(customerId)
            ) {
                "العميل غير موجود"
            }

            require(
                current.updatedAtEpochMillis ==
                    expectedUpdatedAtEpochMillis
            ) {
                "بيانات العميل تغيرت؛ افتحها من جديد"
            }

            check(
                dao.update(
                    current.copy(
                        name = name,
                        location = location,
                        phone = number,
                        updatedAtEpochMillis =
                            nextTime(current.updatedAtEpochMillis)
                    )
                ) == 1
            )

            recordChange(AuditAction.CUSTOMER_CHANGE, current.id, "تعديل العميل: $name")
            current.id
        }
    }

    override suspend fun savePackage(
        draft: PackageDraft,
        packageId: String?,
        expectedUpdatedAtEpochMillis: Long?
    ): String = database.withTransaction {

        val name = clean(draft.name)
        val normalized = normalName(name)

        require(name.isNotEmpty() && normalized.isNotEmpty()) {
            "اسم الباقة مطلوب"
        }

        require(
            draft.nominalValueMinor > 0L &&
                draft.generalSettlementPriceMinor > 0L
        ) {
            "القيمة الاسمية والسعر العام يجب أن يكونا أكبر من الصفر"
        }

        require((draft.colorArgb ushr 24) == 255) {
            "اختر لونًا غير شفاف"
        }

        val dao = database.packageDao()

        if (packageId == null) {
            require(expectedUpdatedAtEpochMillis == null)

            val id = UUID.randomUUID().toString()

            dao.insert(
                PackageEntity(
                    id = id,
                    name = name,
                    normalizedName = normalized,
                    nominalValueMinor = draft.nominalValueMinor,
                    generalSettlementPriceMinor =
                        draft.generalSettlementPriceMinor,
                    colorArgb = draft.colorArgb,
                    createdAtEpochMillis =
                        System.currentTimeMillis()
                )
            )
            recordChange(AuditAction.PACKAGE_CHANGE, id, "إضافة الباقة: $name")
            id
        } else {
            val current = requireNotNull(
                dao.findById(packageId)
            ) {
                "الباقة غير موجودة"
            }

            require(
                current.updatedAtEpochMillis ==
                    expectedUpdatedAtEpochMillis
            ) {
                "بيانات الباقة تغيرت؛ افتحها من جديد"
            }

            check(
                dao.update(
                    current.copy(
                        name = name,
                        normalizedName = normalized,
                        nominalValueMinor =
                            draft.nominalValueMinor,
                        generalSettlementPriceMinor =
                            draft.generalSettlementPriceMinor,
                        colorArgb = draft.colorArgb,
                        updatedAtEpochMillis =
                            nextTime(current.updatedAtEpochMillis)
                    )
                ) == 1
            )

            recordChange(AuditAction.PACKAGE_CHANGE, current.id, "تعديل الباقة: $name")
            current.id
        }
    }

    override suspend fun setCustomerActive(
        customerId: String,
        active: Boolean,
        expectedUpdatedAtEpochMillis: Long
    ) = database.withTransaction {

        val dao = database.customerDao()
        val current = requireNotNull(
            dao.findById(customerId)
        ) {
            "العميل غير موجود"
        }

        require(
            current.updatedAtEpochMillis ==
                expectedUpdatedAtEpochMillis
        ) {
            "بيانات العميل تغيرت؛ أعد المحاولة"
        }

        if (current.isActive != active) {
            check(
                dao.update(
                    current.copy(
                        isActive = active,
                        updatedAtEpochMillis =
                            nextTime(current.updatedAtEpochMillis)
                    )
                ) == 1
            )
            recordChange(AuditAction.CUSTOMER_CHANGE, customerId,
                if (active) "تفعيل العميل: ${current.name}" else "إيقاف العميل: ${current.name}")
        }
    }

    override suspend fun setPackageActive(
        packageId: String,
        active: Boolean,
        expectedUpdatedAtEpochMillis: Long
    ) = database.withTransaction {

        val dao = database.packageDao()
        val current = requireNotNull(
            dao.findById(packageId)
        ) {
            "الباقة غير موجودة"
        }

        require(
            current.updatedAtEpochMillis ==
                expectedUpdatedAtEpochMillis
        ) {
            "بيانات الباقة تغيرت؛ أعد المحاولة"
        }

        if (current.isActive != active) {
            check(
                dao.update(
                    current.copy(
                        isActive = active,
                        updatedAtEpochMillis =
                            nextTime(current.updatedAtEpochMillis)
                    )
                ) == 1
            )
            recordChange(AuditAction.PACKAGE_CHANGE, packageId,
                if (active) "تفعيل الباقة: ${current.name}" else "إيقاف الباقة: ${current.name}")
        }
    }

    override suspend fun removeCustomer(
        customerId: String,
        expectedUpdatedAtEpochMillis: Long
    ): RemovalResult = database.withTransaction {
        val current = requireNotNull(database.customerDao().findById(customerId)) {
            "العميل غير موجود"
        }
        require(current.updatedAtEpochMillis == expectedUpdatedAtEpochMillis) {
            "تغير العميل؛ افتح القائمة مجددًا"
        }
        val purge = database.historyPurgeDao()
        require(purge.unsettledCustomer(customerId) == 0) {
            "لا يمكن حذف العميل: توجد كروت غير مباعة. قم بمحاسبتها أولًا"
        }
        require(purge.customerCentralHistory(customerId) == 0) {
            "لا يمكن حذف العميل: لديه حركات مخزون مركزي قديمة يجب تسويتها أولًا"
        }
        // Ordered deletes satisfy RESTRICT foreign keys, including self-linked reversals.
        purge.clearCustomerOptional(customerId)
        purge.clearCustomerMemberships(customerId)
        purge.clearCustomerAudit(customerId)
        purge.clearCustomerStockCorrections(customerId)
        purge.clearCustomerStock(customerId)
        purge.clearCustomerFinanceReversals(customerId)
        purge.clearCustomerFinance(customerId)
        purge.clearCustomerCardCorrections(customerId)
        purge.clearCustomerCards(customerId)
        purge.clearCustomerLines(customerId)
        purge.clearCustomerGroups(customerId)
        purge.recountArchives()
        purge.removeEmptyArchives()
        purge.deleteCustomer(customerId)
        recordChange(AuditAction.CUSTOMER_CHANGE, customerId, "حذف العميل: ${current.name}")
        RemovalResult.DELETED
    }

    override suspend fun removePackage(
        packageId: String,
        expectedUpdatedAtEpochMillis: Long
    ): RemovalResult = database.withTransaction {
        val current = requireNotNull(database.packageDao().findById(packageId)) {
            "الباقة غير موجودة"
        }
        require(current.updatedAtEpochMillis == expectedUpdatedAtEpochMillis) {
            "تغيرت الباقة؛ افتح القائمة مجددًا"
        }
        val purge = database.historyPurgeDao()
        require(purge.unsettledPackage(packageId) == 0) {
            "لا يمكن حذف الباقة: توجد كروت غير مباعة لدى أحد العملاء"
        }
        require(purge.mixedPackageSettlements(packageId) == 0) {
            "لا يمكن حذف الباقة: توجد محاسبة مشتركة مع باقات أخرى"
        }
        require(purge.packageCentralHistory(packageId) == 0) {
            "لا يمكن حذف الباقة: لديها حركات مخزون مركزي قديمة"
        }
        val groupIds = purge.packageGroupIds(packageId)
        val impactedCustomers = purge.affectedCustomersOfPackage(packageId)
        purge.clearPackageOptional(packageId)
        purge.clearPackageMemberships(packageId)
        purge.clearPackageAudit(packageId)
        purge.clearPackageStockCorrections(packageId)
        purge.clearPackageStock(packageId)
        purge.clearPackagePolicy(packageId)
        purge.clearPackageFinanceReversals(packageId)
        purge.clearPackageFinance(packageId)
        purge.clearPackageCardCorrections(packageId)
        purge.clearPackageCards(packageId)
        purge.clearPackageLines(packageId)
        groupIds.forEach { purge.deletePackageGroup(it) }
        require(impactedCustomers.all { database.financialDao().currentDebt(it) >= 0L }) {
            "لا يمكن الحذف: توجد قيود مالية قديمة مشتركة يجب الحفاظ على سلامتها"
        }
        purge.recountArchives()
        purge.removeEmptyArchives()
        purge.deletePackage(packageId)
        recordChange(AuditAction.PACKAGE_CHANGE, packageId, "حذف الباقة: ${current.name}")
        RemovalResult.DELETED
    }
}
