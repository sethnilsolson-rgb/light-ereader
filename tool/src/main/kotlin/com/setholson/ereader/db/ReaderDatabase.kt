package com.setholson.ereader.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookProgressEntity::class,
        BookBookmarkEntity::class,
        DictEntryEntity::class,
        ReaderSettingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun progressDao(): BookProgressDao
    abstract fun bookmarkDao(): BookBookmarkDao
    abstract fun dictDao(): DictDao
    abstract fun settingDao(): ReaderSettingDao
}
