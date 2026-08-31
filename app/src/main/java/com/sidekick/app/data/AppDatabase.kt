package com.sidekick.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sidekick.app.data.dao.ConversationDao
import com.sidekick.app.data.dao.ProviderConfigDao
import com.sidekick.app.data.dao.TeammateDao
import com.sidekick.app.data.dao.TurnDao

/**
 * Sidekick's local database. Four entities:
 *  - [ConversationEntity] — chat threads
 *  - [TurnEntity]         — messages inside a thread
 *  - [TeammateEntity]     — the seeded persona library
 *  - [ProviderConfigEntity] — provider endpoint + credentials
 *
 * Schema version is `1`. M3 will replace [fallbackToDestructiveMigration] with
 * a real [androidx.room.migration.Migration] chain when the agent loop lands.
 *
 * The factory function [provideDatabase] is the single entry point M2 callers
 * (e.g. the [com.sidekick.app.ui.ConversationViewModel]) use to obtain a
 * singleton — DI framework wiring is M3's problem.
 */
@Database(
    entities = [
        ConversationEntity::class,
        TurnEntity::class,
        TeammateEntity::class,
        ProviderConfigEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun turnDao(): TurnDao
    abstract fun teammateDao(): TeammateDao
    abstract fun providerConfigDao(): ProviderConfigDao
}

/**
 * Build (or return the cached) [AppDatabase] for [context]. Callers should
 * hoist this onto the [android.app.Application] lifecycle in M3 — for M2,
 * the [com.sidekick.app.ui.ConversationViewModel] calls it directly.
 */
fun provideDatabase(context: Context): AppDatabase = Room
    .databaseBuilder(context.applicationContext, AppDatabase::class.java, "sidekick.db")
    .fallbackToDestructiveMigration()
    .build()
