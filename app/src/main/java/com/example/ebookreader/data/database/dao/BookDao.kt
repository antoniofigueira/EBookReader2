package com.example.ebookreader.data.database.dao

import androidx.room.*
import com.example.ebookreader.data.database.entities.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<BookEntity>)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET readingProgress = :progress, currentPage = :currentPage, lastReadTimestamp = :timestamp WHERE id = :bookId")
    suspend fun updateReadingProgress(bookId: String, progress: Float, currentPage: Int, timestamp: Long)

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun updateFavoriteStatus(bookId: String, isFavorite: Boolean)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: String)

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: String): BookEntity?

    @Query("SELECT * FROM books ORDER BY lastReadTimestamp DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun getTotalBookCount(): Int

    @Query("SELECT DISTINCT author FROM books ORDER BY author ASC")
    suspend fun getAllAuthors(): List<String>
}