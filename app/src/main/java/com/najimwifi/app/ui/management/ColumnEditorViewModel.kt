package com.najimwifi.app.ui.management

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.dao.ColumnDefinitionDao
import com.najimwifi.app.data.local.entity.ColumnDefinitionEntity
import com.najimwifi.app.domain.model.ColumnTarget
import com.najimwifi.app.domain.model.ColumnValueType
import com.najimwifi.app.domain.model.RequiredColumnKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ColumnEditorUiState(
    val target: ColumnTarget = ColumnTarget.PACKAGE_JOURNAL,
    val columns: List<ColumnDefinitionEntity> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null
)

/** Display metadata only; accounting events remain unchanged. */
class ColumnEditorViewModel(
    private val database: NajimDatabase
) : ViewModel() {

    private val dao: ColumnDefinitionDao =
        database.columnDefinitionDao()

    private val selectedTarget = MutableStateFlow(
        ColumnTarget.PACKAGE_JOURNAL
    )

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ColumnEditorUiState> = combine(
        dao.observeColumns(ColumnTarget.PACKAGE_JOURNAL),
        dao.observeColumns(ColumnTarget.ARCHIVE),
        selectedTarget,
        busy,
        message
    ) { journal, archive, target, isBusy, notice ->
        ColumnEditorUiState(
            target = target,
            columns = if (
                target == ColumnTarget.PACKAGE_JOURNAL
            ) journal else archive,
            busy = isBusy,
            message = notice
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        ColumnEditorUiState()
    )

    init {
        runChange(successMessage = null) {
            database.withTransaction {
                for (target in ColumnTarget.entries) {
                    val keys = when (target) {
                        ColumnTarget.PACKAGE_JOURNAL -> listOf(
                            RequiredColumnKey.DATE,
                            RequiredColumnKey.OPERATION,
                            RequiredColumnKey.CUSTOMER_NAME,
                            RequiredColumnKey.PACKAGE_NAME,
                            RequiredColumnKey.NOMINAL_VALUE,
                            RequiredColumnKey.WITH_CUSTOMER,
                            RequiredColumnKey.REMAINING,
                            RequiredColumnKey.DELIVERED,
                            RequiredColumnKey.SOLD,
                            RequiredColumnKey.AMOUNT_DUE,
                            RequiredColumnKey.EVENT_QUANTITY,
                            RequiredColumnKey.PAYMENT_AMOUNT
                        )

                        ColumnTarget.ARCHIVE -> listOf(
                            RequiredColumnKey.DATE,
                            RequiredColumnKey.OPERATION,
                            RequiredColumnKey.CUSTOMER_NAME,
                            RequiredColumnKey.PACKAGE_NAME,
                            RequiredColumnKey.EVENT_QUANTITY,
                            RequiredColumnKey.REMAINING,
                            RequiredColumnKey.SOLD,
                            RequiredColumnKey.AMOUNT_DUE,
                            RequiredColumnKey.PAYMENT_AMOUNT
                        )
                    }

                    keys.forEachIndexed { index, key ->
                        dao.insertRequiredIfAbsent(
                            ColumnDefinitionEntity(
                                id = "required:${target.name}:${key.name}",
                                target = target,
                                title = defaultLabel(key),
                                displayOrder = index,
                                valueType = defaultValueType(key),
                                requiredKey = key,
                                isActive = true
                            )
                        )
                    }
                }
            }
        }
    }

    fun select(target: ColumnTarget) {
        selectedTarget.value = target
    }

    fun dismissMessage() {
        message.value = null
    }

    fun renameLabel(
        column: ColumnDefinitionEntity,
        title: String,
        onSuccess: () -> Unit
    ) = runChange(onSuccess = onSuccess) {
        val cleaned = validateTitle(title)

        database.withTransaction {
            val latest = requireColumn(column.id)

            require(
                latest.target == selectedTarget.value
            ) {
                "الجدول تغير"
            }

            check(
                dao.renameDisplayLabel(
                    latest.id,
                    cleaned
                ) == 1
            )
        }
    }

    fun addOptional(
        title: String,
        type: ColumnValueType,
        position: Int,
        onSuccess: () -> Unit
    ) = runChange(onSuccess = onSuccess) {
        val cleaned = validateTitle(title)

        require(type != ColumnValueType.MONEY_MINOR) {
            "الأعمدة الاختيارية ليست قيودًا مالية"
        }

        require(position >= 0) {
            "ترتيب العمود غير صحيح"
        }

        database.withTransaction {
            dao.insertColumn(
                ColumnDefinitionEntity(
                    id = UUID.randomUUID().toString(),
                    target = selectedTarget.value,
                    title = cleaned,
                    displayOrder = position,
                    valueType = type,
                    isActive = true
                )
            )
        }
    }

    fun editOptional(
        column: ColumnDefinitionEntity,
        title: String,
        position: Int,
        onSuccess: () -> Unit
    ) = runChange(onSuccess = onSuccess) {
        val cleaned = validateTitle(title)

        require(position >= 0)

        database.withTransaction {
            val latest = requireColumn(column.id)

            require(
                latest.target == selectedTarget.value &&
                    latest.requiredKey == null
            ) {
                "لا يمكن تغيير نوع أو ترتيب العمود الإلزامي"
            }

            check(
                dao.editOptionalColumn(
                    latest.id,
                    cleaned,
                    position,
                    latest.isActive
                ) == 1
            )
        }
    }

    fun setOptionalVisible(
        column: ColumnDefinitionEntity,
        visible: Boolean
    ) = runChange {
        database.withTransaction {
            val latest = requireColumn(column.id)

            require(
                latest.target == selectedTarget.value &&
                    latest.requiredKey == null
            ) {
                "لا يمكن إخفاء عمود إلزامي"
            }

            check(
                if (visible) {
                    dao.editOptionalColumn(
                        latest.id,
                        latest.title,
                        latest.displayOrder,
                        true
                    ) == 1
                } else {
                    dao.hideOptionalColumn(
                        latest.id
                    ) == 1
                }
            )
        }
    }

    private suspend fun requireColumn(
        id: String
    ): ColumnDefinitionEntity =
        requireNotNull(dao.findColumn(id)) {
            "العمود لم يعد موجودًا"
        }

    private fun validateTitle(text: String): String {
        val cleaned = text.trim().replace(
            Regex("\\s+"),
            " "
        )

        require(
            cleaned.isNotEmpty() &&
                cleaned.length <= 64
        ) {
            "عنوان العمود مطلوب، وبحد أقصى 64 حرفًا"
        }

        return cleaned
    }

    private fun runChange(
        onSuccess: () -> Unit = {},
        successMessage: String? = "تم حفظ إعدادات الأعمدة",
        operation: suspend () -> Unit
    ) {
        if (busy.value) return

        busy.value = true
        message.value = null

        viewModelScope.launch {
            try {
                operation()
                message.value = successMessage
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value =
                    error.message?.takeIf {
                        it.isNotBlank()
                    } ?: "تعذر تنفيذ العملية"
            } finally {
                busy.value = false
            }
        }
    }

    private fun defaultLabel(
        key: RequiredColumnKey
    ): String = when (key) {
        RequiredColumnKey.DATE -> "التاريخ"
        RequiredColumnKey.PACKAGE_NAME -> "الباقة"
        RequiredColumnKey.NOMINAL_VALUE -> "القيمة الاسمية"
        RequiredColumnKey.WITH_CUSTOMER -> "العهدة"
        RequiredColumnKey.REMAINING -> "المتبقي"
        RequiredColumnKey.DELIVERED -> "المسلّم"
        RequiredColumnKey.SOLD -> "المباع"
        RequiredColumnKey.AMOUNT_DUE -> "المبلغ المستحق"
        RequiredColumnKey.OPERATION -> "العملية"
        RequiredColumnKey.CUSTOMER_NAME -> "العميل"
        RequiredColumnKey.EVENT_QUANTITY -> "كمية الحركة"
        RequiredColumnKey.PAYMENT_AMOUNT -> "المدفوع"
    }

    private fun defaultValueType(
        key: RequiredColumnKey
    ): ColumnValueType = when (key) {
        RequiredColumnKey.NOMINAL_VALUE,
        RequiredColumnKey.AMOUNT_DUE,
        RequiredColumnKey.PAYMENT_AMOUNT ->
            ColumnValueType.MONEY_MINOR

        RequiredColumnKey.WITH_CUSTOMER,
        RequiredColumnKey.REMAINING,
        RequiredColumnKey.DELIVERED,
        RequiredColumnKey.SOLD,
        RequiredColumnKey.EVENT_QUANTITY ->
            ColumnValueType.WHOLE_NUMBER

        else ->
            ColumnValueType.TEXT
    }
}