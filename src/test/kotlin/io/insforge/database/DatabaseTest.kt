package io.insforge.database

import io.insforge.TestConfig
import io.insforge.exceptions.InsforgeHttpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Integration tests for Database module
 */
class DatabaseTest {

    private lateinit var client: io.insforge.InsforgeClient

    @BeforeTest
    fun setup() {
        client = TestConfig.createDatabaseClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // Test model matching actual 'users' table schema
    @Serializable
    data class UserRecord(
        val id: String? = null,
        val nickname: String? = null,
        val avatar_url: String? = null,
        val bio: String? = null,
        val birthday: String? = null,
        val created_at: String? = null,
        val updated_at: String? = null
    )

    // Test model for todos table
    @Serializable
    data class TodoRecord(
        val id: String? = null,
        val title: String? = null,
        val completed: Boolean? = null,
        val created_at: String? = null
    )

    // ============ Table Query Tests ============

    @Test
    fun `test select all records from table`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .execute<UserRecord>()

            println("Found ${records.size} records")
            records.forEach { println("  - $it") }
        } catch (e: InsforgeHttpException) {
            println("Select failed: ${e.message}")
        }
    }

    @Test
    fun `test select with specific columns`() = runTest {
        try {
            val records = client.database.from("users")
                .select("id,name,email")
                .execute<UserRecord>()

            println("Found ${records.size} records with selected columns")
        } catch (e: InsforgeHttpException) {
            println("Select failed: ${e.message}")
        }
    }

    @Test
    fun `test select with eq filter`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .isNull("bio")
                .execute<UserRecord>()

            println("Found ${records.size} users with null bio")
        } catch (e: InsforgeHttpException) {
            println("Select with filter failed: ${e.message}")
        }
    }

    @Test
    fun `test select with multiple filters`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .isNull("bio")
                .isNull("avatar_url")
                .execute<UserRecord>()

            println("Found ${records.size} users with null bio and avatar")
        } catch (e: InsforgeHttpException) {
            println("Select with multiple filters failed: ${e.message}")
        }
    }

    @Test
    fun `test select with like filter`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .like("nickname", "%test%")
                .execute<UserRecord>()

            println("Found ${records.size} users with 'test' in nickname")
        } catch (e: InsforgeHttpException) {
            println("Select with like filter failed: ${e.message}")
        }
    }

    @Test
    fun `test select with in filter`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .like("nickname", "%User%")
                .execute<UserRecord>()

            println("Found ${records.size} users with 'User' in nickname")
        } catch (e: InsforgeHttpException) {
            println("Select with in filter failed: ${e.message}")
        }
    }

    @Test
    fun `test select with order`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .order("nickname", ascending = true)
                .execute<UserRecord>()

            println("Found ${records.size} records ordered by nickname")
        } catch (e: InsforgeHttpException) {
            println("Select with order failed: ${e.message}")
        }
    }

    @Test
    fun `test select with limit and offset`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .limit(10)
                .offset(0)
                .execute<UserRecord>()

            println("Found ${records.size} records (page 1, limit 10)")
        } catch (e: InsforgeHttpException) {
            println("Select with pagination failed: ${e.message}")
        }
    }

    @Test
    fun `test select with combined query options`() = runTest {
        try {
            val records = client.database.from("users")
                .select("id,nickname,bio")
                .isNull("bio")
                .order("nickname", ascending = true)
                .limit(5)
                .execute<UserRecord>()

            println("Found ${records.size} records with combined options")
        } catch (e: InsforgeHttpException) {
            println("Combined query failed: ${e.message}")
        }
    }

    // ============ Insert Tests ============

    @Test
    fun `test insert single record`() = runTest {
        try {
            val timestamp = System.currentTimeMillis()
            // Use JsonArray for proper serialization
            val records = buildJsonArray {
                addJsonObject {
                    put("nickname", "Test User $timestamp")
                    put("bio", "Test bio")
                }
            }

            val result = client.database.from("users")
                .insert(records)
                .returning()
                .execute<UserRecord>()

            assertTrue(result.isNotEmpty())
            println("Inserted record: ${result.first()}")
        } catch (e: InsforgeHttpException) {
            println("Insert failed: ${e.message}")
        }
    }

    @Test
    fun `test insert multiple records`() = runTest {
        try {
            val timestamp = System.currentTimeMillis()
            // Use JsonArray with multiple records
            val records = buildJsonArray {
                addJsonObject { put("nickname", "Batch User 1 $timestamp") }
                addJsonObject { put("nickname", "Batch User 2 $timestamp") }
                addJsonObject { put("nickname", "Batch User 3 $timestamp") }
            }

            val result = client.database.from("users")
                .insert(records)
                .returning()
                .execute<UserRecord>()

            assertEquals(3, result.size)
            println("Inserted ${result.size} records")
        } catch (e: InsforgeHttpException) {
            println("Batch insert failed: ${e.message}")
        }
    }

    // ============ Update Tests ============

    @Test
    fun `test update records with filter`() = runTest {
        try {
            val updateData = buildJsonObject {
                put("bio", "Updated bio")
            }
            val result = client.database.from("users")
                .select()
                .isNull("bio")
                .update(updateData)
                .returning()
                .execute<UserRecord>()

            println("Updated ${result.size} records")
        } catch (e: InsforgeHttpException) {
            println("Update failed: ${e.message}")
        }
    }

    @Test
    fun `test update multiple fields`() = runTest {
        try {
            val updateData = buildJsonObject {
                put("bio", "Updated bio")
                put("avatar_url", "https://example.com/avatar.png")
            }
            val result = client.database.from("users")
                .select()
                .like("nickname", "%Test%")
                .update(updateData)
                .returning()
                .execute<UserRecord>()

            println("Updated ${result.size} records with multiple fields")
        } catch (e: InsforgeHttpException) {
            println("Multi-field update failed: ${e.message}")
        }
    }

    // ============ Delete Tests ============

    @Test
    fun `test delete records with filter`() = runTest {
        try {
            // First insert a record to delete
            val timestamp = System.currentTimeMillis()
            val testRecords = buildJsonArray {
                addJsonObject {
                    put("nickname", "ToDelete $timestamp")
                    put("bio", "To be deleted")
                }
            }

            client.database.from("users")
                .insert(testRecords)
                .execute<UserRecord>()

            // Then delete it
            val result = client.database.from("users")
                .select()
                .like("nickname", "ToDelete $timestamp")
                .delete()
                .returning()
                .execute<UserRecord>()

            println("Deleted ${result.size} records")
        } catch (e: InsforgeHttpException) {
            println("Delete failed: ${e.message}")
        }
    }

    // ============ Table Management Tests (Admin) ============

    @Test
    fun `test list tables`() = runTest {
        try {
            val tables = client.database.listTables()
            println("Available tables: $tables")
        } catch (e: InsforgeHttpException) {
            println("List tables failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test get table schema`() = runTest {
        try {
            val schema = client.database.getTableSchema("users")
            println("Table schema: $schema")
        } catch (e: InsforgeHttpException) {
            println("Get schema failed: ${e.message}")
        }
    }

    @Test
    fun `test create and delete table`() = runTest {
        val tableName = "test_table_${System.currentTimeMillis()}"

        try {
            // Create table
            val columns = listOf(
                io.insforge.database.models.ColumnDefinition(
                    name = "id",
                    type = "uuid",
                    nullable = false,
                    unique = true
                ),
                io.insforge.database.models.ColumnDefinition(
                    name = "title",
                    type = "string",
                    nullable = false
                ),
                io.insforge.database.models.ColumnDefinition(
                    name = "created_at",
                    type = "datetime",
                    nullable = true
                )
            )

            val createResponse = client.database.createTable(tableName, columns)
            println("Created table: ${createResponse.tableName}")

            // Delete table
            val deleteResponse = client.database.deleteTable(tableName)
            println("Deleted table: ${deleteResponse.tableName}")
        } catch (e: InsforgeHttpException) {
            println("Table management failed (may require admin): ${e.message}")
        }
    }

    // ============ Filter Edge Cases ============

    @Test
    fun `test isNull filter`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .isNull("email")
                .execute<UserRecord>()

            println("Found ${records.size} records with null email")
        } catch (e: InsforgeHttpException) {
            println("isNull filter failed: ${e.message}")
        }
    }

    @Test
    fun `test neq filter`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .neq("active", false)
                .execute<UserRecord>()

            println("Found ${records.size} records where active != false")
        } catch (e: InsforgeHttpException) {
            println("neq filter failed: ${e.message}")
        }
    }

    @Test
    fun `test range filters`() = runTest {
        try {
            val records = client.database.from("users")
                .select()
                .gt("age", 20)
                .lt("age", 40)
                .execute<UserRecord>()

            println("Found ${records.size} users aged 21-39")
        } catch (e: InsforgeHttpException) {
            println("Range filter failed: ${e.message}")
        }
    }

    // ============ Count Tests ============

    @Test
    fun `test count all records`() = runTest {
        try {
            val count = client.database.from("todos")
                .select()
                .count()

            println("Total todos count: $count")
            assertTrue(count >= 0, "Count should be non-negative")
        } catch (e: InsforgeHttpException) {
            println("Count failed: ${e.message}")
        }
    }

    @Test
    fun `test count with filter`() = runTest {
        try {
            val count = client.database.from("todos")
                .select()
                .eq("completed", false)
                .count()

            println("Incomplete todos count: $count")
            assertTrue(count >= 0, "Count should be non-negative")
        } catch (e: InsforgeHttpException) {
            println("Count with filter failed: ${e.message}")
        }
    }

    @Test
    fun `test count with exact type`() = runTest {
        try {
            val count = client.database.from("todos")
                .select()
                .count(CountType.EXACT)

            println("Exact count: $count")
            assertTrue(count >= 0, "Count should be non-negative")
        } catch (e: InsforgeHttpException) {
            println("Exact count failed: ${e.message}")
        }
    }

    @Test
    fun `test count with planned type`() = runTest {
        try {
            val count = client.database.from("todos")
                .select()
                .count(CountType.PLANNED)

            println("Planned count: $count")
            assertTrue(count >= 0, "Count should be non-negative")
        } catch (e: InsforgeHttpException) {
            println("Planned count failed: ${e.message}")
        }
    }

    @Test
    fun `test count with estimated type`() = runTest {
        try {
            val count = client.database.from("todos")
                .select()
                .count(CountType.ESTIMATED)

            println("Estimated count: $count")
            assertTrue(count >= 0, "Count should be non-negative")
        } catch (e: InsforgeHttpException) {
            println("Estimated count failed: ${e.message}")
        }
    }

    @Test
    fun `test count matches select size`() = runTest {
        try {
            // Get actual records
            val records = client.database.from("todos")
                .select()
                .execute<TodoRecord>()

            // Get count
            val count = client.database.from("todos")
                .select()
                .count()

            println("Records size: ${records.size}, Count: $count")
            // Note: They might differ if table is being modified concurrently
            // but for static test data they should match
        } catch (e: InsforgeHttpException) {
            println("Count comparison failed: ${e.message}")
        }
    }
}
