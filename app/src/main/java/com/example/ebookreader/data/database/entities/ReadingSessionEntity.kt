package com.example.ebookreader.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReadingSessionEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val pageNumber: Int,
    val positionInChapter: Int,
    val readingTimeSeconds: Int,
    val timestamp: Long,
    val deviceId: String
)