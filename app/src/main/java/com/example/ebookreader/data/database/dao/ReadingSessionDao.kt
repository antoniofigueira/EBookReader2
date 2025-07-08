package com.example.ebookreader.data.database.dao

import androidx.room.*
import com.example.ebookreader.data.database.entities.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY timestamp DESC")
    fun getSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSessionForBook(bookId: String): ReadingSessionEntity?

    @Query("DELETE FROM reading_sessions WHERE bookId = :bookId")
    suspend fun deleteSessionsForBook(bookId: String)
}