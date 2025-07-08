package com.example.ebookreader.domain.model

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val filePath: String,
    val fileUri: String? = null,
    val coverPath: String? = null,
    val format: BookFormat,
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

enum class BookFormat(val extension: String, val mimeType: String) {
    EPUB("epub", "application/epub+zip"),
    MOBI("mobi", "application/x-mobipocket-ebook"),
    PDF("pdf", "application/pdf"),
    TXT("txt", "text/plain"),
    FB2("fb2", "application/x-fictionbook+xml"),
    HTML("html", "text/html"),
    MD("md", "text/markdown")
}