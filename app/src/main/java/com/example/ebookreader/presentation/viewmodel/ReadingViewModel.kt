package com.example.ebookreader.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ebookreader.data.database.dao.BookDao
import com.example.ebookreader.data.parser.EpubParser
import com.example.ebookreader.data.preferences.ReadingPreferences
import com.example.ebookreader.domain.model.Book
import com.example.ebookreader.presentation.ui.reader.ReadingUiState
import com.example.ebookreader.presentation.ui.reader.ReadingTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ReadingViewModel @Inject constructor(
    private val bookDao: BookDao,
    @ApplicationContext private val context: Context,
    private val readingPreferences: ReadingPreferences,
    private val epubParser: EpubParser
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReadingUiState>(ReadingUiState.Loading)
    val uiState: StateFlow<ReadingUiState> = _uiState.asStateFlow()

    private var currentBook: Book? = null
    private var bookContent: String = ""
    private var pages: List<String> = emptyList()

    // Screen metrics for responsive design
    private var currentScreenMetrics = ScreenMetrics()

    data class ScreenMetrics(
        val width: Int = 1080,
        val height: Int = 1920,
        val density: Float = 3f
    )

    // Load saved preferences when ViewModel is created
    init {
        loadSavedPreferences()
    }

    private fun loadSavedPreferences() {
        viewModelScope.launch {
            try {
                // Load preferences but don't trigger immediate updates
                // They will be applied when a book is loaded
            } catch (e: Exception) {
                // Handle preference loading errors gracefully
            }
        }
    }

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            try {
                _uiState.value = ReadingUiState.Loading

                val bookEntity = bookDao.getBookById(bookId)
                if (bookEntity == null) {
                    _uiState.value = ReadingUiState.Error("Book not found")
                    return@launch
                }

                // Convert entity to domain model
                val book = Book(
                    id = bookEntity.id,
                    title = bookEntity.title,
                    author = bookEntity.author,
                    filePath = bookEntity.filePath,
                    fileUri = bookEntity.fileUri,
                    coverPath = bookEntity.coverPath,
                    format = com.example.ebookreader.domain.model.BookFormat.values()
                        .find { it.extension == bookEntity.format }
                        ?: com.example.ebookreader.domain.model.BookFormat.TXT,
                    fileSize = bookEntity.fileSize,
                    totalPages = bookEntity.totalPages,
                    totalChapters = bookEntity.totalChapters,
                    readingProgress = bookEntity.readingProgress,
                    currentPage = bookEntity.currentPage,
                    lastReadTimestamp = bookEntity.lastReadTimestamp,
                    isFavorite = bookEntity.isFavorite,
                    series = bookEntity.series,
                    seriesNumber = bookEntity.seriesNumber,
                    category = bookEntity.category,
                    language = bookEntity.language,
                    createdAt = bookEntity.createdAt,
                    updatedAt = bookEntity.updatedAt
                )

                currentBook = book

                // Load book content based on format
                bookContent = loadBookContent(book) ?: "Could not load book content"

                // Load saved preferences
                val savedFontSize = readingPreferences.fontSize.first()
                val savedThemeName = readingPreferences.theme.first()
                val savedTheme = try {
                    ReadingTheme.valueOf(savedThemeName)
                } catch (e: Exception) {
                    ReadingTheme.LIGHT
                }

                // Split content into pages with saved font size
                pages = splitContentOptimized(bookContent, savedFontSize)

                // Start from saved page or beginning
                val startPage = if (book.currentPage > 0) book.currentPage else 1

                _uiState.value = ReadingUiState.Success(
                    book = book,
                    currentPageContent = pages.getOrNull(startPage - 1) ?: "No content available",
                    currentPage = startPage,
                    totalPages = pages.size,
                    fontSize = savedFontSize,
                    theme = savedTheme
                )

            } catch (e: Exception) {
                _uiState.value = ReadingUiState.Error("Failed to load book: ${e.message}")
            }
        }
    }

    fun nextPage() {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success && currentState.currentPage < currentState.totalPages) {
            val newPage = currentState.currentPage + 1
            _uiState.value = currentState.copy(
                currentPageContent = pages.getOrNull(newPage - 1) ?: "",
                currentPage = newPage
            )
            saveReadingProgress(newPage)
        }
    }

    fun previousPage() {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success && currentState.currentPage > 1) {
            val newPage = currentState.currentPage - 1
            _uiState.value = currentState.copy(
                currentPageContent = pages.getOrNull(newPage - 1) ?: "",
                currentPage = newPage
            )
            saveReadingProgress(newPage)
        }
    }

    fun changeFontSize(newSize: Float) {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            // Save preference immediately
            viewModelScope.launch {
                readingPreferences.setFontSize(newSize)
            }

            // Update UI immediately for instant response
            _uiState.value = currentState.copy(fontSize = newSize)

            // Recalculate pages in background
            viewModelScope.launch {
                try {
                    val oldTotalPages = pages.size
                    pages = splitContentOptimized(bookContent, newSize)

                    // Maintain reading position proportionally
                    val progress = (currentState.currentPage - 1).toFloat() / oldTotalPages.toFloat()
                    val newCurrentPage = ((progress * pages.size) + 1).toInt().coerceIn(1, pages.size)

                    _uiState.value = currentState.copy(
                        currentPageContent = pages.getOrNull(newCurrentPage - 1) ?: "",
                        currentPage = newCurrentPage,
                        totalPages = pages.size,
                        fontSize = newSize
                    )
                } catch (e: Exception) {
                    // If recalculation fails, keep the font size change
                    _uiState.value = currentState.copy(fontSize = newSize)
                }
            }
        }
    }

    fun changeTheme(newTheme: ReadingTheme) {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            // Save preference immediately
            viewModelScope.launch {
                readingPreferences.setTheme(newTheme.name)
            }

            // Update UI immediately
            _uiState.value = currentState.copy(theme = newTheme)
        }
    }

    fun updateScreenMetrics(screenWidth: Int, screenHeight: Int, density: Float) {
        currentScreenMetrics = ScreenMetrics(screenWidth, screenHeight, density)

        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            viewModelScope.launch {
                try {
                    // Recalculate pages with new screen dimensions
                    val oldTotalPages = pages.size
                    pages = splitContentOptimized(
                        content = bookContent,
                        fontSize = currentState.fontSize
                    )

                    // Maintain relative position
                    val progress = (currentState.currentPage - 1).toFloat() / oldTotalPages.toFloat()
                    val newCurrentPage = ((progress * pages.size) + 1).toInt().coerceIn(1, pages.size)

                    _uiState.value = currentState.copy(
                        currentPageContent = pages.getOrNull(newCurrentPage - 1) ?: "",
                        currentPage = newCurrentPage,
                        totalPages = pages.size
                    )
                } catch (e: Exception) {
                    // Handle screen metric update errors gracefully
                }
            }
        }
    }

    // Enhanced content loading with full format support
    private suspend fun loadBookContent(book: Book): String? {
        return try {
            when (book.format) {
                com.example.ebookreader.domain.model.BookFormat.TXT -> {
                    loadTextFile(book)
                }
                com.example.ebookreader.domain.model.BookFormat.EPUB -> {
                    loadEpubContent(book)
                }
                com.example.ebookreader.domain.model.BookFormat.PDF -> {
                    // PDF support - placeholder for now
                    "PDF reading support coming soon!\n\nFile: ${book.title}\nAuthor: ${book.author}\n\nThis feature will be added in a future update with proper PDF rendering and text extraction."
                }
                com.example.ebookreader.domain.model.BookFormat.MOBI -> {
                    "MOBI reading support coming soon!\n\nFile: ${book.title}\nAuthor: ${book.author}\n\nMOBI format support will be added in a future update."
                }
                com.example.ebookreader.domain.model.BookFormat.FB2 -> {
                    "FB2 reading support coming soon!\n\nFile: ${book.title}\nAuthor: ${book.author}\n\nFB2 format support will be added in a future update."
                }
                com.example.ebookreader.domain.model.BookFormat.HTML -> {
                    loadHtmlFile(book)
                }
                com.example.ebookreader.domain.model.BookFormat.MD -> {
                    loadMarkdownFile(book)
                }
                else -> {
                    "This ${book.format.extension.uppercase()} file format is not yet fully supported.\n\nFilename: ${book.title}\nAuthor: ${book.author}\n\nSupported formats: TXT, EPUB, HTML, MD\nComing soon: PDF, MOBI, FB2"
                }
            }
        } catch (e: Exception) {
            "Error loading content: ${e.message}\n\nPlease try reimporting this book from the library."
        }
    }

    private suspend fun loadTextFile(book: Book): String? {
        return if (book.filePath.startsWith("/")) {
            // New system: file stored in app-private storage
            val file = File(book.filePath)
            if (file.exists()) {
                file.readText()
            } else {
                "File not found. The book may have been moved or deleted."
            }
        } else {
            // Old system: URI-based (try with permissions)
            val uri = Uri.parse(book.fileUri ?: book.filePath)

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Permission not available
            }

            val inputStream = context.contentResolver.openInputStream(uri)
            inputStream?.bufferedReader()?.use { it.readText() }
                ?: "Could not read file. Please reimport this book from the library."
        }
    }

    private suspend fun loadEpubContent(book: Book): String? {
        return try {
            val epubContent = if (book.filePath.startsWith("/")) {
                // File stored in app-private storage
                epubParser.parseEpubFromFile(book.filePath)
            } else {
                // URI-based
                val uri = Uri.parse(book.fileUri ?: book.filePath)
                epubParser.parseEpub(context, uri)
            }

            epubContent?.fullText ?: "Could not parse EPUB content. The file may be corrupted or use an unsupported EPUB variant."
        } catch (e: Exception) {
            e.printStackTrace()
            "Error parsing EPUB: ${e.message}\n\nPlease ensure this is a valid EPUB file and try reimporting."
        }
    }

    private suspend fun loadHtmlFile(book: Book): String? {
        return try {
            val htmlContent = if (book.filePath.startsWith("/")) {
                val file = File(book.filePath)
                if (file.exists()) file.readText() else null
            } else {
                val uri = Uri.parse(book.fileUri ?: book.filePath)
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }

            htmlContent?.let { extractTextFromHtml(it) }
                ?: "Could not read HTML file."
        } catch (e: Exception) {
            "Error loading HTML: ${e.message}"
        }
    }

    private suspend fun loadMarkdownFile(book: Book): String? {
        return try {
            val markdownContent = if (book.filePath.startsWith("/")) {
                val file = File(book.filePath)
                if (file.exists()) file.readText() else null
            } else {
                val uri = Uri.parse(book.fileUri ?: book.filePath)
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }

            markdownContent?.let { convertMarkdownToText(it) }
                ?: "Could not read Markdown file."
        } catch (e: Exception) {
            "Error loading Markdown: ${e.message}"
        }
    }

    private fun extractTextFromHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun convertMarkdownToText(markdown: String): String {
        return markdown
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "") // Headers
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1") // Bold
            .replace(Regex("\\*(.*?)\\*"), "$1") // Italic
            .replace(Regex("~~(.*?)~~"), "$1") // Strikethrough
            .replace(Regex("`(.*?)`"), "$1") // Inline code
            .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1") // Links
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "") // List items
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "") // Numbered lists
            .replace(Regex("^>\\s*", RegexOption.MULTILINE), "") // Blockquotes
            .trim()
    }

    private fun splitContentOptimized(content: String, fontSize: Float = 16f): List<String> {
        try {
            // Calculate characters per page based on font size and screen metrics
            val baseCharsPerPage = calculateBaseCharsPerPage()
            val fontMultiplier = when {
                fontSize <= 12f -> 1.6f
                fontSize <= 14f -> 1.3f
                fontSize <= 16f -> 1.0f
                fontSize <= 18f -> 0.8f
                fontSize <= 20f -> 0.65f
                fontSize <= 22f -> 0.55f
                fontSize <= 24f -> 0.45f
                else -> 0.4f
            }

            val charactersPerPage = (baseCharsPerPage * fontMultiplier).toInt()

            // Split by sentences and paragraphs for natural breaks
            val sentences = content.split(Regex("(?<=[.!?])\\s+"))
            val pages = mutableListOf<String>()
            var currentPage = StringBuilder()
            var currentLength = 0

            for (sentence in sentences) {
                val sentenceLength = sentence.length + 1 // +1 for space

                // Check if adding this sentence would exceed page limit
                if (currentLength + sentenceLength > charactersPerPage && currentPage.isNotEmpty()) {
                    // Finish current page
                    val pageContent = currentPage.toString().trim()
                    if (pageContent.isNotEmpty()) {
                        pages.add(pageContent)
                    }
                    currentPage = StringBuilder()
                    currentLength = 0
                }

                currentPage.append(sentence).append(" ")
                currentLength += sentenceLength
            }

            // Add the final page
            if (currentPage.isNotEmpty()) {
                val pageContent = currentPage.toString().trim()
                if (pageContent.isNotEmpty()) {
                    pages.add(pageContent)
                }
            }

            return pages.ifEmpty { listOf("No content available") }

        } catch (e: Exception) {
            // Fallback to simple word-based splitting
            return splitContentSimple(content, fontSize)
        }
    }

    private fun calculateBaseCharsPerPage(): Int {
        // Calculate based on screen metrics
        val screenArea = currentScreenMetrics.width * currentScreenMetrics.height
        return when {
            screenArea > 2_000_000 -> 1200 // Large screens
            screenArea > 1_000_000 -> 1000 // Medium screens
            else -> 800 // Small screens
        }
    }

    private fun splitContentSimple(content: String, fontSize: Float = 16f): List<String> {
        val baseCharsPerPage = calculateBaseCharsPerPage()
        val fontMultiplier = when {
            fontSize <= 12f -> 1.5f
            fontSize <= 16f -> 1.0f
            fontSize <= 20f -> 0.7f
            else -> 0.5f
        }

        val charactersPerPage = (baseCharsPerPage * fontMultiplier).toInt()
        val words = content.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val pages = mutableListOf<String>()
        var currentPage = StringBuilder()
        var currentLength = 0

        for (word in words) {
            val wordLength = word.length + 1 // +1 for space

            if (currentLength + wordLength > charactersPerPage && currentPage.isNotEmpty()) {
                pages.add(currentPage.toString().trim())
                currentPage = StringBuilder()
                currentLength = 0
            }

            currentPage.append(word).append(" ")
            currentLength += wordLength
        }

        if (currentPage.isNotEmpty()) {
            pages.add(currentPage.toString().trim())
        }

        return pages.ifEmpty { listOf("No content available") }
    }

    private fun saveReadingProgress(currentPage: Int) {
        viewModelScope.launch {
            try {
                currentBook?.let { book ->
                    val progress = if (pages.isNotEmpty()) {
                        currentPage.toFloat() / pages.size.toFloat()
                    } else {
                        0f
                    }
                    bookDao.updateReadingProgress(
                        book.id,
                        progress,
                        currentPage,
                        System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                // Handle database errors gracefully
            }
        }
    }

    // Get reading statistics for current page
    fun getPageStatistics(): String? {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            val currentPageContent = currentState.currentPageContent
            val words = currentPageContent.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            val chars = currentPageContent.length
            val estimatedReadingTime = kotlin.math.ceil(words.toDouble() / 200).toInt() // 200 words per minute

            return "Words: $words • Characters: $chars • Est. time: ${estimatedReadingTime}min"
        }
        return null
    }

    // Jump to specific page
    fun goToPage(pageNumber: Int) {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            val targetPage = pageNumber.coerceIn(1, currentState.totalPages)
            _uiState.value = currentState.copy(
                currentPageContent = pages.getOrNull(targetPage - 1) ?: "",
                currentPage = targetPage
            )
            saveReadingProgress(targetPage)
        }
    }

    // Jump to specific percentage
    fun goToPercentage(percentage: Float) {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success && currentState.totalPages > 0) {
            val targetPage = ((percentage / 100f) * currentState.totalPages).toInt().coerceIn(1, currentState.totalPages)
            goToPage(targetPage)
        }
    }

    // Get current reading progress as percentage
    fun getReadingProgressPercentage(): Int {
        val currentState = _uiState.value
        return if (currentState is ReadingUiState.Success && currentState.totalPages > 0) {
            ((currentState.currentPage.toFloat() / currentState.totalPages) * 100).toInt()
        } else {
            0
        }
    }

    // Get book information
    fun getBookInfo(): Map<String, Any>? {
        val currentState = _uiState.value
        return if (currentState is ReadingUiState.Success) {
            mapOf(
                "title" to currentState.book.title,
                "author" to currentState.book.author,
                "format" to currentState.book.format.extension.uppercase(),
                "fileSize" to currentState.book.fileSize,
                "totalPages" to currentState.totalPages,
                "currentPage" to currentState.currentPage,
                "progress" to getReadingProgressPercentage(),
                "wordsOnPage" to currentState.currentPageContent.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            )
        } else null
    }

    // Reset reading position to beginning
    fun resetToBeginning() {
        goToPage(1)
    }

    // Go to end of book
    fun goToEnd() {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            goToPage(currentState.totalPages)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up resources if needed
        currentBook = null
        bookContent = ""
        pages = emptyList()
    }
}