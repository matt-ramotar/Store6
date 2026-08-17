package org.mobilenativefoundation.store6.sqldelight.sample

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertTrue
import org.mobilenativefoundation.store6.sqldelight.sample.db.SampleDatabase

class UserUpsertTest {
    @Test
    fun upsertUpdatesTheParentWithoutDeletingReferencingRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SampleDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            driver.execute(
                null,
                """
                CREATE TABLE profile (
                    id TEXT NOT NULL PRIMARY KEY,
                    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE
                )
                """.trimIndent(),
                0,
            )
            val database = SampleDatabase(driver)
            database.userQueries.upsert("one", "First", "first@example.com")
            driver.execute(null, "INSERT INTO profile(id, user_id) VALUES ('profile', 'one')", 0)

            database.userQueries.upsert("one", "Second", "second@example.com")

            val profileStillExists =
                driver.executeQuery(
                    null,
                    "SELECT 1 FROM profile WHERE id = 'profile'",
                    { cursor -> QueryResult.Value(cursor.next().value) },
                    0,
                ).value
            assertTrue(profileStillExists)
        } finally {
            driver.close()
        }
    }
}
