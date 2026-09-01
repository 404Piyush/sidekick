package com.sidekick.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sidekick.app.data.dao.ConversationDao
import com.sidekick.app.data.dao.ProviderConfigDao
import com.sidekick.app.data.dao.TeammateDao
import com.sidekick.app.data.dao.ToolCallDao
import com.sidekick.app.data.dao.TurnDao

/**
 * Sidekick's local database. Five entities (M3 added [ToolCallEntity]):
 *  - [ConversationEntity] — chat threads
 *  - [TurnEntity]         — messages inside a thread
 *  - [ToolCallEntity]     — tool invocations emitted by the agent loop
 *  - [TeammateEntity]     — the seeded persona library
 *  - [ProviderConfigEntity] — provider endpoint + credentials
 *
 * Schema version is `3`.
 *  - 1 → 2 ([MIGRATION_1_2]) added the `tool_calls` table.
 *  - 2 → 3 ([MIGRATION_2_3]) added `modelPath` and `backend` columns on
 *    `provider_configs` for the M7 on-device LiteRT-LM provider.
 *
 * The factory function [provideDatabase] is the single entry point M2+
 * callers (e.g. [com.sidekick.app.ui.ConversationViewModel]) use to
 * obtain a singleton — DI framework wiring is deferred.
 */
@Database(
    entities = [
        ConversationEntity::class,
        TurnEntity::class,
        ToolCallEntity::class,
        TeammateEntity::class,
        ProviderConfigEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun turnDao(): TurnDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun teammateDao(): TeammateDao
    abstract fun providerConfigDao(): ProviderConfigDao
}

/**
 * Schema bump from M2 → M3: add the `tool_calls` table.
 *
 * Hand-rolled SQL because `exportSchema` is off (the project doesn't ship
 * the JSON schema files Room would otherwise read). The shape mirrors
 * [ToolCallEntity]:
 *  - `id INTEGER PRIMARY KEY AUTOINCREMENT`
 *  - `turnId INTEGER NOT NULL` with `FOREIGN KEY(turnId) REFERENCES turns(id) ON DELETE CASCADE`
 *  - `toolName TEXT NOT NULL`
 *  - `argsJson TEXT NOT NULL`
 *  - `resultJson TEXT` (nullable)
 *  - `createdAt INTEGER NOT NULL`
 *  - `INDEX index_tool_calls_turnId ON tool_calls(turnId)`
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tool_calls` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `turnId` INTEGER NOT NULL,
                `toolName` TEXT NOT NULL,
                `argsJson` TEXT NOT NULL,
                `resultJson` TEXT,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`turnId`) REFERENCES `turns`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_calls_turnId` ON `tool_calls` (`turnId`)",
        )
    }
}

/**
 * Schema bump from v2 → v3: add two columns to `provider_configs` for
 * the M7 on-device LiteRT-LM provider.
 *  - `modelPath TEXT` — absolute path to a `.litertlm` model file
 *    (null for non-on-device rows, since they don't need a local file)
 *  - `backend TEXT` — serialised `Backend` enum name (`"NPU"`, `"GPU"`,
 *    `"CPU"`); null for non-on-device rows
 *
 * Both columns are nullable with `NULL` as the default, so the migration
 * is non-destructive: pre-M7 rows keep their existing `providerKind` /
 * `baseUrl` / `modelName` / `apiKey` fields, and the new columns sit
 * empty until the user opts into the on-device path via Settings.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `provider_configs` ADD COLUMN `modelPath` TEXT")
        db.execSQL("ALTER TABLE `provider_configs` ADD COLUMN `backend` TEXT")
    }
}

/**
 * Build (or return the cached) [AppDatabase] for [context]. The 1 → 2
 * and 2 → 3 migrations are registered explicitly — Room's
 * `fallbackToDestructiveMigration` is NOT used because real users may
 * have M2 data on devices and we don't want to wipe it.
 */
fun provideDatabase(context: Context): AppDatabase = Room
    .databaseBuilder(context.applicationContext, AppDatabase::class.java, "sidekick.db")
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    .build()