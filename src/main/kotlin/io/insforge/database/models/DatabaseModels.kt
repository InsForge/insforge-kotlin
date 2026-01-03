package io.insforge.database.models

import kotlinx.serialization.Serializable

// ============ Table Management ============

@Serializable
data class CreateTableRequest(
    val tableName: String,
    val columns: List<ColumnDefinition>
)

@Serializable
data class ColumnDefinition(
    val name: String,
    val type: String, // string, datetime, integer, float, boolean, uuid, json, file
    val nullable: Boolean = true,
    val unique: Boolean = false,
    val defaultValue: String? = null,
    val foreignKey: ForeignKeyDefinition? = null
)

@Serializable
data class ForeignKeyDefinition(
    val table: String,
    val column: String,
    val onDelete: String = "NO ACTION" // CASCADE, SET NULL, NO ACTION, RESTRICT
)

@Serializable
data class CreateTableResponse(
    val message: String,
    val tableName: String
)

@Serializable
data class TableSchema(
    val tableName: String,
    val columns: List<ColumnInfo>
)

@Serializable
data class ColumnInfo(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val unique: Boolean,
    val default: String? = null,
    val isPrimaryKey: Boolean,
    val foreignKey: ForeignKeyInfo? = null
)

@Serializable
data class ForeignKeyInfo(
    val table: String,
    val column: String,
    val onDelete: String
)

@Serializable
data class TableSchemaUpdate(
    val addColumns: List<AddColumnOperation>? = null,
    val dropColumns: List<String>? = null,
    val updateColumns: List<UpdateColumnOperation>? = null,
    val addForeignKeys: List<AddForeignKeyOperation>? = null,
    val dropForeignKeys: List<String>? = null,
    val renameTable: RenameTableOperation? = null
)

@Serializable
data class AddColumnOperation(
    val columnName: String,
    val type: String,
    val isNullable: Boolean = true,
    val isUnique: Boolean = false,
    val defaultValue: String? = null
)

@Serializable
data class UpdateColumnOperation(
    val columnName: String,
    val newColumnName: String? = null,
    val defaultValue: String? = null
)

@Serializable
data class AddForeignKeyOperation(
    val columnName: String,
    val foreignKey: ForeignKeyConstraint
)

@Serializable
data class ForeignKeyConstraint(
    val referenceTable: String,
    val referenceColumn: String,
    val onDelete: String = "RESTRICT",
    val onUpdate: String = "RESTRICT"
)

@Serializable
data class RenameTableOperation(
    val newTableName: String
)

@Serializable
data class UpdateTableResponse(
    val message: String,
    val tableName: String,
    val operations: List<String>
)

@Serializable
data class DeleteTableResponse(
    val message: String,
    val tableName: String
)
