package com.example.ebookreader.di

import android.content.Context
import androidx.room.Room
import com.example.ebookreader.data.database.BookDatabase
import com.example.ebookreader.data.database.dao.BookDao
import com.example.ebookreader.data.database.dao.ReadingSessionDao
import com.example.ebookreader.data.parser.EpubParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideBookDatabase(@ApplicationContext context: Context): BookDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            BookDatabase::class.java,
            "book_database"
        ).build()
    }

    @Provides
    fun provideBookDao(database: BookDatabase): BookDao {
        return database.bookDao()
    }

    @Provides
    fun provideReadingSessionDao(database: BookDatabase): ReadingSessionDao {
        return database.readingSessionDao()
    }

    @Provides
    @Singleton
    fun provideEpubParser(): EpubParser {
        return EpubParser()
    }
}