package com.setholson.ereader.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "book_progress")
data class BookProgressEntity(
    @PrimaryKey
    val bookId: String,
    val title: String,
    val author: String,
    val format: String,
    val currentPosition: Int = 0,
    val totalPositions: Int = 1,
    val scrollIndex: Int = 0,
    val progressPercent: Int = 0,
    val lastReadAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "book_bookmarks")
data class BookBookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: String,
    val bookTitle: String,
    val format: String,
    val positionIndex: Int,
    val positionLabel: String,
    val snippet: String,
    val scrollPermille: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "dict_entries",
    indices = [
        Index(value = ["dictId", "headword"]),
        Index(value = ["headword"]),
    ],
)
data class DictEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val dictId: String,
    val headword: String,
    val definition: String,
)

@Entity(tableName = "reader_settings")
data class ReaderSettingEntity(
    @PrimaryKey
    val key: String,
    val value: String,
)

data class DictCountRow(
    val dictId: String,
    val entryCount: Int,
)
