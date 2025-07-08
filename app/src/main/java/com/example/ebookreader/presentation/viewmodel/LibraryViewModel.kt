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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

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

                // Copy file to app-private storage for permanent access
                val copiedFilePath = copyFileToAppStorage(uri, fileName)

                if (copiedFilePath == null) {
                    // Fallback: keep original URI and hope permissions persist
                    _isImporting.value = false
                    return@launch
                }

                // Try to extract metadata based on file type
                val (title, author) = when (format) {
                    BookFormat.EPUB -> extractEpubMetadata(uri, fileName)
                    BookFormat.TXT -> extractTxtMetadata(uri, fileName)
                    BookFormat.PDF -> extractPdfMetadata(uri, fileName)
                    else -> extractMetadataFromFilename(fileName)
                }

                val bookEntity = BookEntity(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    author = author,
                    filePath = copiedFilePath, // Use copied file path
                    fileUri = copiedFilePath,  // Use copied file path
                    format = format.extension,
                    fileSize = fileSize,
                    totalPages = 0,
                    readingProgress = 0f,
                    lastReadTimestamp = System.currentTimeMillis()
                )
                bookDao.insertBook(bookEntity)
            } catch (e: Exception) {
                e.printStackTrace()
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

    // EPUB metadata extraction
    private suspend fun extractEpubMetadata(uri: Uri, fileName: String): Pair<String, String> {
        return try {
            // For now, we'll implement basic EPUB reading
            // EPUB files are ZIP archives with metadata in META-INF/container.xml
            // and content.opf files

            // Simplified: Try to read some content and guess
            val inputStream = context.contentResolver.openInputStream(uri)
            inputStream?.use {
                // For now, fall back to filename until we implement full EPUB parsing
                extractMetadataFromFilename(fileName)
            } ?: extractMetadataFromFilename(fileName)
        } catch (e: Exception) {
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
}