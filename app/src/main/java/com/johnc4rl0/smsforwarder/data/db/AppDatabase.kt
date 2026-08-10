package com.johnc4rl0.smsforwarder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ForwardJobEntity::class,
        PartResultEntity::class,
        QuotaEventEntity::class,
        DedupEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun forwardJobDao(): ForwardJobDao
    abstract fun partResultDao(): PartResultDao
    abstract fun quotaEventDao(): QuotaEventDao
    abstract fun dedupDao(): DedupDao

    companion object {
        const val DB_NAME = "sms_forwarder.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun buildInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
