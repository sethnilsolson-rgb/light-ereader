package com.setholson.ereader.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookProgressDao {
    @Query("SELECT * FROM book_progress ORDER BY lastReadAt DESC")
    fun getAllProgress(): Flow<List<BookProgressEntity>>

    @Query("SELECT * FROM book_progress ORDER BY lastReadAt DESC LIMIT 1")
    fun getMostRecentProgress(): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId LIMIT 1")
    suspend fun getProgressSync(bookId: String): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: BookProgressEntity)

    @Query("UPDATE book_progress SET scrollIndex = :scrollPermille, lastReadAt = :updatedAt WHERE bookId = :bookId")
    suspend fun updateScrollIndex(bookId: String, scrollPermille: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM book_progress WHERE bookId = :bookId")
    suspend fun deleteProgress(bookId: String)
}

@Dao
interface BookBookmarkDao {
    @Query("SELECT * FROM book_bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookBookmarkEntity>>

    @Query("SELECT COUNT(*) FROM book_bookmarks")
    fun getBookmarkCount(): Flow<Int>

    @Query("SELECT * FROM book_bookmarks WHERE bookId = :bookId AND positionIndex = :positionIndex ORDER BY scrollPermille ASC")
    fun getBookmarksForPosition(bookId: String, positionIndex: Int): Flow<List<BookBookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM book_bookmarks WHERE bookId = :bookId AND positionIndex = :positionIndex)")
    fun isPositionBookmarked(bookId: String, positionIndex: Int): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookBookmarkEntity)

    @Query("DELETE FROM book_bookmarks WHERE bookId = :bookId AND positionIndex = :positionIndex")
    suspend fun deletePositionBookmark(bookId: String, positionIndex: Int)

    @Query("DELETE FROM book_bookmarks WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)

    @Delete
    suspend fun deleteBookmark(bookmark: BookBookmarkEntity)
}

@Dao
interface DictDao {
    @Query("SELECT * FROM dict_entries WHERE dictId = :dictId AND headword = :headword LIMIT 3")
    suspend fun lookupInDict(dictId: String, headword: String): List<DictEntryEntity>

    @Query("SELECT * FROM dict_entries WHERE headword = :headword LIMIT 12")
    suspend fun lookupAcrossAll(headword: String): List<DictEntryEntity>

    @Query("SELECT dictId, COUNT(*) AS entryCount FROM dict_entries GROUP BY dictId")
    fun getCountsByDict(): Flow<List<DictCountRow>>

    @Query("SELECT dictId, COUNT(*) AS entryCount FROM dict_entries GROUP BY dictId")
    suspend fun getCountsByDictSync(): List<DictCountRow>

    @Query("SELECT COUNT(*) FROM dict_entries WHERE dictId = :dictId")
    suspend fun countForDict(dictId: String): Int

    @Query("SELECT COUNT(*) FROM dict_entries")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(entries: List<DictEntryEntity>)

    @Query("DELETE FROM dict_entries WHERE dictId = :dictId")
    suspend fun deleteByDict(dictId: String)

    @Query("DELETE FROM dict_entries")
    suspend fun clearAll()
}

@Dao
interface ReaderSettingDao {
    @Query("SELECT * FROM reader_settings")
    fun getAllSettings(): Flow<List<ReaderSettingEntity>>

    @Query("SELECT value FROM reader_settings WHERE `key` = :key LIMIT 1")
    suspend fun getSettingSync(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: ReaderSettingEntity)
}
