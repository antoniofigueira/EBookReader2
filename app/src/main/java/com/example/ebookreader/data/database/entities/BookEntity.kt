package com.example.ebookreader.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val author: String,
    val filePath: String,
    val fileUri: String? = null,
    val coverPath: String? = null,
    val format: String,
    val fileSize: Long,
    val totalPages: Int = 0,
    val totalChapters: Int = 0,
    val readingProgress: Float = 0f,
    val currentPage: Int = 0,
    val lastReadTimestamp: Long = 0L,
    val isFavorite: Boolean = false,
    val series: String? = null,
    val seriesNumber: Int? = null,
    val category: String? = null,
    val language: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)