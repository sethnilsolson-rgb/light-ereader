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
        BookNoteEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun progressDao(): BookProgressDao
    abstract fun bookmarkDao(): BookBookmarkDao
    abstract fun dictDao(): DictDao
    abstract fun settingDao(): ReaderSettingDao
    abstract fun noteDao(): BookNoteDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `book_bookmarks` ADD COLUMN `scrollPermille` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_notes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` TEXT NOT NULL,
                        `bookTitle` TEXT NOT NULL,
                        `format` TEXT NOT NULL,
                        `positionIndex` INTEGER NOT NULL,
                        `chapterTitle` TEXT NOT NULL,
                        `anchorsEncoded` TEXT NOT NULL,
                        `quotedText` TEXT NOT NULL,
                        `noteText` TEXT NOT NULL,
                        `scrollPermille` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_book_notes_bookId_positionIndex`
                    ON `book_notes` (`bookId`, `positionIndex`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_book_notes_updatedAt`
                    ON `book_notes` (`updatedAt`)
                    """.trimIndent()
                )
            }
        }
    }
}
