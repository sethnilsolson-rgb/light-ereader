package com.setholson.ereader.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookProgressEntity::class,
        BookBookmarkEntity::class,
        DictEntryEntity::class,
        ReaderSettingEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun progressDao(): BookProgressDao
    abstract fun bookmarkDao(): BookBookmarkDao
    abstract fun dictDao(): DictDao
    abstract fun settingDao(): ReaderSettingDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `book_bookmarks` ADD COLUMN `scrollPermille` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
