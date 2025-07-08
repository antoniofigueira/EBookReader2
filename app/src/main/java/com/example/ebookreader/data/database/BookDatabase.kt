package com.example.ebookreader.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.example.ebookreader.data.database.dao.BookDao
import com.example.ebookreader.data.database.dao.ReadingSessionDao
import com.example.ebookreader.data.database.entities.BookEntity
import com.example.ebookreader.data.database.entities.ReadingSessionEntity

@Database(
    entities = [BookEntity::class, ReadingSessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BookDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun readingSessionDao(): ReadingSessionDao

    companion object {
        @Volatile
        private var INSTANCE: BookDatabase? = null

        fun getDatabase(context: Context): BookDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    "book_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}