package com.najimwifi.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.najimwifi.app.domain.model.ColumnTarget
import com.najimwifi.app.domain.model.ColumnValueType
import com.najimwifi.app.domain.model.RequiredColumnKey

@Entity(
    tableName = "column_definitions",
    indices = [
        Index(value = ["target", "displayOrder"]),
        Index(value = ["target", "requiredKey"], unique = true)
    ]
)
@TypeConverters(ColumnConverters::class)
data class ColumnDefinitionEntity(
    @PrimaryKey val id: String,
    val target: ColumnTarget,
    val title: String,
    val displayOrder: Int,
    val valueType: ColumnValueType,
    val requiredKey: RequiredColumnKey? = null,
    val isActive: Boolean = true
) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(displayOrder >= 0)
        require(requiredKey == null || isActive)
    }
}

enum class OptionalRowKind {
    CARD_MOVEMENT,
    SETTLEMENT_GROUP,
    SETTLEMENT_LINE,
    FINANCIAL_ENTRY
}

/** Fixed, typed EAV cells: user content is never used as an SQL identifier. */
@Entity(
    tableName = "optional_column_cells",
    primaryKeys = ["columnId", "rowKind", "rowId"],
    foreignKeys = [ForeignKey(
        entity = ColumnDefinitionEntity::class,
        parentColumns = ["id"],
        childColumns = ["columnId"],
        onDelete = ForeignKey.RESTRICT
    )],
    indices = [Index(value = ["rowKind", "rowId"])]
)
@TypeConverters(ColumnConverters::class)
data class OptionalColumnCellEntity(
    val columnId: String,
    val rowKind: OptionalRowKind,
    val rowId: String,
    val valueType: ColumnValueType,
    val textValue: String? = null,
    val numberValue: Long? = null
) {
    init {
        require(columnId.isNotBlank() && rowId.isNotBlank())
        when (valueType) {
            ColumnValueType.TEXT -> require(
                textValue != null && numberValue == null
            )
            ColumnValueType.WHOLE_NUMBER -> require(
                textValue == null && numberValue != null
            )
            ColumnValueType.MONEY_MINOR -> require(
                textValue == null &&
                    numberValue != null &&
                    numberValue >= 0L
            )
        }
    }
}

class ColumnConverters {
    @TypeConverter
    fun fromTarget(value: ColumnTarget): String = value.name

    @TypeConverter
    fun toTarget(value: String): ColumnTarget =
        ColumnTarget.valueOf(value)

    @TypeConverter
    fun fromValueType(value: ColumnValueType): String = value.name

    @TypeConverter
    fun toValueType(value: String): ColumnValueType =
        ColumnValueType.valueOf(value)

    @TypeConverter
    fun fromRequiredKey(value: RequiredColumnKey?): String? =
        value?.name

    @TypeConverter
    fun toRequiredKey(value: String?): RequiredColumnKey? =
        value?.let(RequiredColumnKey::valueOf)

    @TypeConverter
    fun fromRowKind(value: OptionalRowKind): String = value.name

    @TypeConverter
    fun toRowKind(value: String): OptionalRowKind =
        OptionalRowKind.valueOf(value)
}