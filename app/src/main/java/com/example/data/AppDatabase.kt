package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CaptainEntity::class,
        OrderEntity::class,
        ShiftEntity::class,
        DailyWalletEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun captainDao(): CaptainDao

    abstract fun orderDao(): OrderDao

    abstract fun shiftDao(): ShiftDao

    abstract fun dailyWalletDao(): DailyWalletDao

    companion object {

        val MIGRATION_7_8 = object : Migration(7, 8) {

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE orders ADD COLUMN cancelledAt INTEGER NOT NULL DEFAULT 0"
                )

                db.execSQL(
                    "ALTER TABLE orders ADD COLUMN cancellationReason TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {

            override fun migrate(db: SupportSQLiteDatabase) {

                // Captain profile fields
                db.execSQL(
                    "ALTER TABLE captain_profile ADD COLUMN address TEXT NOT NULL DEFAULT ''"
                )

                db.execSQL(
                    "ALTER TABLE captain_profile ADD COLUMN accountType TEXT NOT NULL DEFAULT 'captain'"
                )

                db.execSQL(
                    "ALTER TABLE captain_profile ADD COLUMN availabilityStatus TEXT NOT NULL DEFAULT 'offline'"
                )

                db.execSQL(
                    "ALTER TABLE captain_profile ADD COLUMN shiftStatus TEXT NOT NULL DEFAULT 'outside_shift'"
                )

                // Order and merchant fields
                db.execSQL(
                    "ALTER TABLE orders ADD COLUMN orderCode TEXT NOT NULL DEFAULT ''"
                )

                db.execSQL(
                    "ALTER TABLE orders ADD COLUMN merchantId TEXT NOT NULL DEFAULT ''"
                )

                db.execSQL(
                    "ALTER TABLE orders ADD COLUMN storeLocationUrl TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {

            return INSTANCE ?: synchronized(this) {

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "elbatal_captain_db"
                )
                    .addMigrations(
                        MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance

                instance
            }
        }
    }
}