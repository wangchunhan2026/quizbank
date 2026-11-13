package com.daily.quizbank.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BankEntity::class, QuestionEntity::class, UsageRecordEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bankDao(): BankDao
    abstract fun questionDao(): QuestionDao
    abstract fun usageDao(): UsageDao

    companion object {
        private const val DB_NAME = "quizbank.db"

        @Volatile
        private var instance: AppDatabase? = null

        /** 建表语句放在迁移对象之前声明，避免初始化顺序上的隐患。 */
        private val SQL_CREATE_USAGE = """
            CREATE TABLE IF NOT EXISTS "usage_records" (
                "id" INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                "timestamp" INTEGER NOT NULL,
                "model" TEXT NOT NULL,
                "promptTokens" INTEGER NOT NULL,
                "completionTokens" INTEGER NOT NULL,
                "cachedTokens" INTEGER NOT NULL,
                "usedImage" INTEGER NOT NULL,
                "costMicros" INTEGER NOT NULL
            )
        """.trimIndent()

        /**
         * 1 → 2：只新增用量表。
         *
         * 必须写真正的迁移，不能依赖 fallbackToDestructiveMigration 兜底 ——
         * 那会把用户已经导入的上千道题直接清空，这种数据丢失不可接受。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(SQL_CREATE_USAGE)
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
