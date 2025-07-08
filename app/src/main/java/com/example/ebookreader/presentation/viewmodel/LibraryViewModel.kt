package com.example.ebookreader.presentation.viewmodel

import java.io.File
import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ebookreader.data.database.dao.BookDao
import com.example.ebookreader.data.database.entities.BookEntity
import com.example.ebookreader.data.parser.EpubParser
//import com.example.ebookreader.data.parser.extractMetadataForLibrary
import com.example.ebookreader.domain.model.Book
import com.example.ebookreader.domain.model.BookFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookDao: BookDao,
    @ApplicationContext private val context: Context,
    private val epubParser: EpubParser
) : ViewModel() {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    init {
        loadBooks()
    }

    private fun loadBooks() {
        viewModelScope.launch {
            bookDao.getAllBooks().collect { bookEntities ->
                _books.value = bookEntities.map { entity ->
                    Book(
                        id = entity.id,
                        title = entity.title,
                        author = entity.author,
                        filePath = entity.filePath,
                        fileUri = entity.fileUri,
                        coverPath = entity.coverPath,
                        format = BookFormat.values().find { it.extension == entity.format } ?: BookFormat.TXT,
                        fileSize = entity.fileSize,
                        totalPages = entity.totalPages,
                        totalChapters = entity.totalChapters,
                        readingProgress = entity.readingProgress,
                        currentPage = entity.currentPage,
                        lastReadTimestamp = entity.lastReadTimestamp,
                        isFavorite = entity.isFavorite,
                        series = entity.series,
                        seriesNumber = entity.seriesNumber,
                        category = entity.category,
                        language = entity.language,
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt
                    )
                }
            }
        }
    }

    fun importBookFromUri(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatus.value = "Starting import..."

            try {
                // Grant persistent permission first (attempt)
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Permission might not be available, we'll copy the file instead
                }

                val documentFile = DocumentFile.fromSingleUri(context, uri)
                val fileName = documentFile?.name ?: "Unknown Book"
                val fileSize = documentFile?.length() ?: 0L
                val format = getBookFormat(fileName)

                _importStatus.value = "Copying file to storage..."

                // Copy file to app-private storage for permanent access
                val copiedFilePath = copyFileToAppStorage(uri, fileName)

                if (copiedFilePath == null) {
                    _importStatus.value = "Failed to copy file"
                    _isImporting.value = false
                    return@launch
                }

                _importStatus.value = "Extracting metadata..."

                // Use enhanced metadata extraction
                val (title, author) = extractEnhancedMetadata(uri, fileName, format)

                _importStatus.value = "Saving to database..."

                val bookEntity = BookEntity(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    author = author,
                    filePath = copiedFilePath,
                    fileUri = copiedFilePath,
                    format = format.extension,
                    fileSize = fileSize,
                    totalPages = 0,
                    readingProgress = 0f,
                    lastReadTimestamp = System.currentTimeMillis()
                )
                bookDao.insertBook(bookEntity)

                _importStatus.value = "Import completed successfully!"

                // Clear status after a delay
                kotlinx.coroutines.delay(2000)
                _importStatus.value = null

            } catch (e: Exception) {
                e.printStackTrace()
                _importStatus.value = "Import failed: ${e.message}"

                // Clear error after a delay
                kotlinx.coroutines.delay(3000)
                _importStatus.value = null
            } finally {
                _isImporting.value = false
            }
        }
    }

    private suspend fun copyFileToAppStorage(uri: Uri, fileName: String): String? {
        return try {
            // Create books directory in app-private storage
            val booksDir = File(context.filesDir, "books")
            if (!booksDir.exists()) {
                booksDir.mkdirs()
            }

            // Create unique filename to avoid conflicts
            val sanitizedFileName = sanitizeFileName(fileName)
            val uniqueFileName = "${UUID.randomUUID()}_$sanitizedFileName"
            val outputFile = File(booksDir, uniqueFileName)

            // Copy file content
            val inputStream = context.contentResolver.openInputStream(uri)
            inputStream?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Return the absolute path
            outputFile.absolutePath

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        // Remove or replace invalid characters for file system
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .take(100) // Limit length
    }

    // Enhanced metadata extraction that works with all supported formats
    private suspend fun extractEnhancedMetadata(uri: Uri, fileName: String, format: BookFormat): Pair<String, String> {
        return when (format) {
            BookFormat.EPUB -> {
                extractEpubMetadata(uri, fileName)
            }
            BookFormat.TXT -> extractTxtMetadata(uri, fileName)
            BookFormat.PDF -> extractPdfMetadata(uri, fileName)
            else -> extractMetadataFromFilename(fileName)
        }
    }

    // EPUB metadata extraction using the new parser
    private suspend fun extractEpubMetadata(uri: Uri, fileName: String): Pair<String, String> {
        return try {
            // Use the new EPUB parser for better metadata extraction
            epubParser.extractMetadataForLibrary(context, uri)
                ?: extractMetadataFromFilename(fileName)
        } catch (e: Exception) {
            e.printStackTrace()
            extractMetadataFromFilename(fileName)
        }
    }

    // TXT metadata extraction
    private suspend fun extractTxtMetadata(uri: Uri, fileName: String): Pair<String, String> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { reader ->
                // Read first few lines to look for title/author info
                val lines = mutableListOf<String>()
                repeat(10) { // Read first 10 lines
                    val line = reader.readLine()
                    if (line != null) lines.add(line.trim())
                    else return@repeat
                }
                lines
            } ?: emptyList()

            // Look for common patterns in TXT files
            var title: String? = null
            var author: String? = null

            for (line in content) {
                when {
                    line.lowercase().startsWith("title:") -> {
                        title = line.substring(6).trim()
                    }
                    line.lowercase().startsWith("author:") -> {
                        author = line.substring(7).trim()
                    }
                    line.lowercase().startsWith("by ") && author == null -> {
                        author = line.substring(3).trim()
                    }
                    // First non-empty line could be title if no explicit title found
                    title == null && line.isNotBlank() && !line.lowercase().contains("author") -> {
                        title = line
                    }
                }
            }

            Pair(
                title?.take(100) ?: extractTitleFromFilename(fileName),
                author?.take(50) ?: "Unknown Author"
            )

        } catch (e: Exception) {
            extractMetadataFromFilename(fileName)
        }
    }

    // PDF metadata extraction (basic)
    private suspend fun extractPdfMetadata(uri: Uri, fileName: String): Pair<String, String> {
        return try {
            // PDF metadata extraction would require a PDF library
            // For now, fall back to filename parsing
            extractMetadataFromFilename(fileName)
        } catch (e: Exception) {
            extractMetadataFromFilename(fileName)
        }
    }

    // Improved filename parsing as fallback (Calibre format: "Title - Author")
    private fun extractMetadataFromFilename(fileName: String): Pair<String, String> {
        val nameWithoutExtension = fileName.substringBeforeLast('.', fileName)

        // Pattern: "Title - Author" (Calibre default)
        if (nameWithoutExtension.contains(" - ")) {
            val parts = nameWithoutExtension.split(" - ", limit = 2)
            return Pair(parts[0].trim(), parts[1].trim()) // title, author
        }

        // Pattern: "Title by Author" (alternative)
        if (nameWithoutExtension.contains(" by ")) {
            val parts = nameWithoutExtension.split(" by ", limit = 2)
            return Pair(parts[0].trim(), parts[1].trim()) // title, author
        }

        // Default: use filename as title
        return Pair(nameWithoutExtension, "Unknown Author")
    }

    private fun extractTitleFromFilename(fileName: String): String {
        return fileName.substringBeforeLast('.', fileName)
    }

    private fun getBookFormat(fileName: String): BookFormat {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "txt" -> BookFormat.TXT
            "epub" -> BookFormat.EPUB
            "pdf" -> BookFormat.PDF
            "mobi" -> BookFormat.MOBI
            "html", "htm" -> BookFormat.HTML
            "md" -> BookFormat.MD
            "fb2" -> BookFormat.FB2
            else -> BookFormat.TXT
        }
    }

    // Add this new function for getting full EPUB content (for reading)
    suspend fun getEpubContent(bookId: String): String? {
        return try {
            val bookEntity = bookDao.getBookById(bookId) ?: return null

            if (bookEntity.format != "epub") return null

            val content = if (bookEntity.filePath.startsWith("/")) {
                // File stored in app-private storage
                epubParser.parseEpubFromFile(bookEntity.filePath)
            } else {
                // URI-based
                val uri = Uri.parse(bookEntity.fileUri ?: bookEntity.filePath)
                epubParser.parseEpub(context, uri)
            }

            content?.fullText
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Delete book function
    fun deleteBook(book: Book) {
        viewModelScope.launch {
            try {
                // Delete file from storage if it exists
                if (book.filePath.startsWith("/")) {
                    val file = File(book.filePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }

                // Delete from database
                bookDao.deleteBookById(book.id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Toggle favorite status
    fun toggleFavorite(book: Book) {
        viewModelScope.launch {
            try {
                bookDao.updateFavoriteStatus(book.id, !book.isFavorite)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Search books
    fun searchBooks(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                loadBooks()
            } else {
                bookDao.searchBooks(query).collect { bookEntities ->
                    _books.value = bookEntities.map { entity ->
                        Book(
                            id = entity.id,
                            title = entity.title,
                            author = entity.author,
                            filePath = entity.filePath,
                            fileUri = entity.fileUri,
                            coverPath = entity.coverPath,
                            format = BookFormat.values().find { it.extension == entity.format } ?: BookFormat.TXT,
                            fileSize = entity.fileSize,
                            totalPages = entity.totalPages,
                            totalChapters = entity.totalChapters,
                            readingProgress = entity.readingProgress,
                            currentPage = entity.currentPage,
                            lastReadTimestamp = entity.lastReadTimestamp,
                            isFavorite = entity.isFavorite,
                            series = entity.series,
                            seriesNumber = entity.seriesNumber,
                            category = entity.category,
                            language = entity.language,
                            createdAt = entity.createdAt,
                            updatedAt = entity.updatedAt
                        )
                    }
                }
            }
        }
    }

    fun getStorageInfo(): String {
        return try {
            val booksDir = File(context.filesDir, "books")
            if (!booksDir.exists()) {
                "No books stored locally"
            } else {
                val files = booksDir.listFiles() ?: emptyArray()
                val totalSize = files.sumOf { it.length() }
                val sizeInMB = totalSize / (1024 * 1024)

                "${files.size} books stored locally (${sizeInMB}MB)"
            }
        } catch (e: Exception) {
            "Storage info unavailable"
        }
    }

    // Keep the test function for development
    fun addTestBook() {
        viewModelScope.launch {
            val testBook = BookEntity(
                id = UUID.randomUUID().toString(),
                title = "Sample Book ${System.currentTimeMillis() % 1000}",
                author = "Test Author",
                filePath = "/test/path/book.txt",
                format = "txt",
                fileSize = 50000L,
                totalPages = 200,
                readingProgress = (0..100).random() / 100f,
                lastReadTimestamp = System.currentTimeMillis()
            )
            bookDao.insertBook(testBook)
        }
    }

    // Utility function to read file content (for future reading screen)
    fun readFileContent(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            inputStream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Testing function for EPUB parsing (remove after testing)
    fun testEpubParsing(uri: Uri) {
        viewModelScope.launch {
            try {
                val epubContent = epubParser.parseEpub(context, uri)
                if (epubContent != null) {
                    println("✅ EPUB Parsing Success!")
                    println("Title: ${epubContent.metadata.title}")
                    println("Author: ${epubContent.metadata.author}")
                    println("Chapters: ${epubContent.chapters.size}")
                    println("Total text length: ${epubContent.fullText.length} characters")
                    println("First 200 characters: ${epubContent.fullText.take(200)}")
                } else {
                    println("❌ EPUB Parsing Failed - returned null")
                }
            } catch (e: Exception) {
                println("❌ EPUB Parsing Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Get library statistics
    fun getLibraryStats(): Map<String, Any> {
        val currentBooks = _books.value
        val totalBooks = currentBooks.size
        val totalSize = currentBooks.sumOf { it.fileSize }
        val formatCounts = currentBooks.groupBy { it.format }.mapValues { it.value.size }
        val averageProgress = if (totalBooks > 0) {
            currentBooks.map { it.readingProgress }.average()
        } else 0.0

        return mapOf(
            "totalBooks" to totalBooks,
            "totalSizeBytes" to totalSize,
            "totalSizeMB" to (totalSize / (1024 * 1024)),
            "formatCounts" to formatCounts,
            "averageProgress" to averageProgress,
            "recentlyRead" to currentBooks.filter { it.lastReadTimestamp > 0 }.size
        )
    }
}