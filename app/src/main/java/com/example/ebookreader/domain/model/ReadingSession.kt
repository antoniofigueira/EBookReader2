package com.example.ebookreader.domain.model

data class ReadingSession(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val pageNumber: Int,
    val positionInChapter: Int,
    val readingTimeSeconds: Int,
    val timestamp: Long,
    val deviceId: String
)