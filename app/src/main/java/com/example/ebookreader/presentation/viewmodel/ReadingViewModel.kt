package com.example.ebookreader.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ebookreader.data.database.dao.BookDao
import com.example.ebookreader.data.parser.EpubParser
import com.example.ebookreader.data.parser.EpubContent
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
    private var epubContent: EpubContent? = null
    private var isEpubFormat = false

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

    // Define saveReadingProgress function first
    private fun saveReadingProgress(currentPage: Int) {
        viewModelScope.launch {
            try {
                currentBook?.let { book ->
                    val progress = if (isEpubFormat && epubContent != null) {
                        // For EPUB, calculate progress based on chapter count
                        currentPage.toFloat() / (epubContent!!.chapters.size + 1).toFloat()
                    } else if (pages.isNotEmpty()) {
                        // For regular text, calculate based on pages
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
                Log.e("ReadingViewModel", "Error saving reading progress", e)
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
                isEpubFormat = book.format == com.example.ebookreader.domain.model.BookFormat.EPUB

                // Load saved preferences
                val savedFontSize = readingPreferences.fontSize.first()
                val savedThemeName = readingPreferences.theme.first()
                val savedTheme = try {
                    ReadingTheme.valueOf(savedThemeName)
                } catch (e: Exception) {
                    ReadingTheme.LIGHT
                }

                if (isEpubFormat) {
                    // Load EPUB with enhanced content
                    loadEpubContentEnhanced(book, savedFontSize, savedTheme)
                } else {
                    // Load other formats as before
                    loadRegularContent(book, savedFontSize, savedTheme)
                }

            } catch (e: Exception) {
                _uiState.value = ReadingUiState.Error("Failed to load book: ${e.message}")
            }
        }
    }

    private suspend fun loadEpubContentEnhanced(book: Book, fontSize: Float, theme: ReadingTheme) {
        try {
            Log.d("ReadingViewModel", "Loading enhanced EPUB content for: ${book.title}")

            // Parse EPUB with enhanced parser
            epubContent = if (book.filePath.startsWith("/")) {
                Log.d("ReadingViewModel", "Loading EPUB from file path: ${book.filePath}")
                epubParser.parseEpubFromFile(book.filePath)
            } else {
                Log.d("ReadingViewModel", "Loading EPUB from URI: ${book.fileUri}")
                val uri = Uri.parse(book.fileUri ?: book.filePath)
                epubParser.parseEpub(context, uri)
            }

            if (epubContent != null) {
                val content = epubContent!!
                Log.d("ReadingViewModel", "EPUB content loaded successfully")
                Log.d("ReadingViewModel", "Chapters: ${content.chapters.size}")
                Log.d("ReadingViewModel", "Images: ${content.images.size}")
                Log.d("ReadingViewModel", "Has cover: ${content.metadata.coverImageData != null}")

                // Create the complete HTML content with cover and proper formatting
                val fullHtmlContent = createEpubHtmlContent(content, fontSize, theme)

                // For EPUB, we don't split into pages the same way
                // Instead, we provide the full HTML content
                val startPage = if (book.currentPage > 0) book.currentPage else 1

                _uiState.value = ReadingUiState.Success(
                    book = book,
                    currentPageContent = fullHtmlContent,
                    currentPage = startPage,
                    totalPages = content.chapters.size + 1, // +1 for cover
                    fontSize = fontSize,
                    theme = theme
                )
            } else {
                Log.e("ReadingViewModel", "EPUB parsing returned null")
                _uiState.value = ReadingUiState.Error("Could not parse EPUB content. The file may be corrupted or use an unsupported EPUB variant.")
            }
        } catch (e: Exception) {
            Log.e("ReadingViewModel", "Error loading EPUB content", e)
            _uiState.value = ReadingUiState.Error("Failed to load EPUB: ${e.message}")
        }
    }

    private suspend fun loadRegularContent(book: Book, fontSize: Float, theme: ReadingTheme) {
        // Load content based on format
        bookContent = loadBookContent(book) ?: "Could not load book content"
        pages = splitContentOptimized(bookContent, fontSize)
        val startPage = if (book.currentPage > 0) book.currentPage else 1

        _uiState.value = ReadingUiState.Success(
            book = book,
            currentPageContent = pages.getOrNull(startPage - 1) ?: "No content available",
            currentPage = startPage,
            totalPages = pages.size,
            fontSize = fontSize,
            theme = theme
        )
    }

    private fun createEpubHtmlContent(content: EpubContent, fontSize: Float, theme: ReadingTheme): String {
        val themeColors = when (theme) {
            ReadingTheme.LIGHT -> Triple("#FFFFFF", "#000000", "#F5F5F5")
            ReadingTheme.DARK -> Triple("#121212", "#E0E0E0", "#1E1E1E")
            ReadingTheme.SEPIA -> Triple("#F4F1EA", "#5D4E37", "#EAE4D3")
            ReadingTheme.BLACK -> Triple("#000000", "#E0E0E0", "#0D0D0D")
        }

        val (backgroundColor, textColor, surfaceColor) = themeColors

        // Create cover page
        val coverPage = if (content.metadata.coverImageData != null) {
            val base64Cover = Base64.encodeToString(content.metadata.coverImageData, Base64.DEFAULT)
            """
                <div class="cover-page">
                    <img src="data:image/jpeg;base64,$base64Cover" class="cover-image" alt="Cover" />
                    <div class="title">${content.metadata.title}</div>
                    <div class="author">by ${content.metadata.author}</div>
                    ${content.metadata.description?.let { "<div class=\"description\">$it</div>" } ?: ""}
                </div>
            """
        } else {
            """
                <div class="cover-page">
                    <div class="title">${content.metadata.title}</div>
                    <div class="author">by ${content.metadata.author}</div>
                    ${content.metadata.description?.let { "<div class=\"description\">$it</div>" } ?: ""}
                </div>
            """
        }

        // Create chapters with proper formatting
        val chaptersHtml = content.chapters.joinToString("\n") { chapter ->
            """
                <div class="chapter">
                    <h2 class="chapter-title">${chapter.title}</h2>
                    ${chapter.htmlContent}
                </div>
            """
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                <style>
                    body {
                        font-family: 'Roboto', Georgia, serif;
                        font-size: ${fontSize}px;
                        line-height: 1.6;
                        margin: 0;
                        padding: 16px;
                        background-color: $backgroundColor;
                        color: $textColor;
                        max-width: 100%;
                        word-wrap: break-word;
                    }
                    
                    .cover-page {
                        text-align: center;
                        padding: 40px 20px;
                        min-height: 80vh;
                        display: flex;
                        flex-direction: column;
                        justify-content: center;
                        border-bottom: 2px solid $surfaceColor;
                        margin-bottom: 30px;
                    }
                    
                    .cover-image {
                        max-width: 80%;
                        max-height: 60vh;
                        height: auto;
                        margin: 0 auto 20px auto;
                        border-radius: 8px;
                        box-shadow: 0 4px 8px rgba(0,0,0,0.3);
                    }
                    
                    .title {
                        font-size: ${fontSize * 1.8}px;
                        font-weight: bold;
                        margin: 20px 0;
                        color: $textColor;
                    }
                    
                    .author {
                        font-size: ${fontSize * 1.2}px;
                        color: $textColor;
                        opacity: 0.8;
                        margin-bottom: 10px;
                    }
                    
                    .description {
                        font-size: ${fontSize * 0.9}px;
                        color: $textColor;
                        opacity: 0.7;
                        margin-top: 20px;
                        max-width: 80%;
                        margin-left: auto;
                        margin-right: auto;
                        line-height: 1.4;
                    }
                    
                    .chapter {
                        margin-bottom: 40px;
                        padding-bottom: 20px;
                    }
                    
                    .chapter-title {
                        font-size: ${fontSize * 1.4}px;
                        font-weight: bold;
                        margin: 40px 0 20px 0;
                        color: $textColor;
                        border-bottom: 2px solid $surfaceColor;
                        padding-bottom: 10px;
                        text-align: center;
                    }
                    
                    h1, h2, h3, h4, h5, h6 {
                        color: $textColor;
                        margin-top: 1.5em;
                        margin-bottom: 0.5em;
                        font-weight: bold;
                    }
                    
                    h1 { font-size: ${fontSize * 1.6}px; }
                    h2 { font-size: ${fontSize * 1.4}px; }
                    h3 { font-size: ${fontSize * 1.2}px; }
                    h4 { font-size: ${fontSize * 1.1}px; }
                    h5, h6 { font-size: ${fontSize}px; }
                    
                    p {
                        text-align: justify;
                        margin-bottom: 1em;
                        text-indent: 1.5em;
                        line-height: 1.6;
                    }
                    
                    .first-paragraph {
                        text-indent: 0;
                    }
                    
                    img {
                        max-width: 100%;
                        height: auto;
                        display: block;
                        margin: 20px auto;
                        border-radius: 4px;
                    }
                    
                    blockquote {
                        border-left: 4px solid $surfaceColor;
                        margin: 1em 0;
                        padding: 1em;
                        background-color: $surfaceColor;
                        font-style: italic;
                        border-radius: 4px;
                    }
                    
                    ul, ol {
                        margin: 1em 0;
                        padding-left: 2em;
                    }
                    
                    li {
                        margin-bottom: 0.5em;
                        line-height: 1.4;
                    }
                    
                    em { font-style: italic; }
                    strong, b { font-weight: bold; }
                    
                    .center { text-align: center; }
                    .right { text-align: right; }
                    
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        margin: 1em 0;
                    }
                    
                    td, th {
                        border: 1px solid $surfaceColor;
                        padding: 8px;
                        text-align: left;
                    }
                    
                    th {
                        background-color: $surfaceColor;
                        font-weight: bold;
                    }
                    
                    /* Smooth scrolling */
                    html {
                        scroll-behavior: smooth;
                    }
                </style>
            </head>
            <body>
                $coverPage
                $chaptersHtml
            </body>
            </html>
        """
    }

    fun nextPage() {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success && currentState.currentPage < currentState.totalPages) {
            val newPage = currentState.currentPage + 1

            if (isEpubFormat) {
                // For EPUB, just update page counter (scrolling is handled by WebView)
                _uiState.value = currentState.copy(currentPage = newPage)
            } else {
                // For regular text, update content
                _uiState.value = currentState.copy(
                    currentPageContent = pages.getOrNull(newPage - 1) ?: "",
                    currentPage = newPage
                )
            }
            saveReadingProgress(newPage)
        }
    }

    fun previousPage() {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success && currentState.currentPage > 1) {
            val newPage = currentState.currentPage - 1

            if (isEpubFormat) {
                // For EPUB, just update page counter
                _uiState.value = currentState.copy(currentPage = newPage)
            } else {
                // For regular text, update content
                _uiState.value = currentState.copy(
                    currentPageContent = pages.getOrNull(newPage - 1) ?: "",
                    currentPage = newPage
                )
            }
            saveReadingProgress(newPage)
        }
    }

    fun changeFontSize(newSize: Float) {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            viewModelScope.launch {
                readingPreferences.setFontSize(newSize)
            }

            if (isEpubFormat && epubContent != null) {
                // For EPUB, regenerate HTML with new font size
                val newHtmlContent = createEpubHtmlContent(epubContent!!, newSize, currentState.theme)
                _uiState.value = currentState.copy(
                    fontSize = newSize,
                    currentPageContent = newHtmlContent
                )
            } else {
                // For other formats, use existing logic
                _uiState.value = currentState.copy(fontSize = newSize)
                viewModelScope.launch {
                    try {
                        val oldTotalPages = pages.size
                        pages = splitContentOptimized(bookContent, newSize)
                        val progress = (currentState.currentPage - 1).toFloat() / oldTotalPages.toFloat()
                        val newCurrentPage = ((progress * pages.size) + 1).toInt().coerceIn(1, pages.size)

                        _uiState.value = currentState.copy(
                            currentPageContent = pages.getOrNull(newCurrentPage - 1) ?: "",
                            currentPage = newCurrentPage,
                            totalPages = pages.size,
                            fontSize = newSize
                        )
                    } catch (e: Exception) {
                        _uiState.value = currentState.copy(fontSize = newSize)
                    }
                }
            }
        }
    }

    fun changeTheme(newTheme: ReadingTheme) {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            viewModelScope.launch {
                readingPreferences.setTheme(newTheme.name)
            }

            if (isEpubFormat && epubContent != null) {
                // For EPUB, regenerate HTML with new theme
                val newHtmlContent = createEpubHtmlContent(epubContent!!, currentState.fontSize, newTheme)
                _uiState.value = currentState.copy(
                    theme = newTheme,
                    currentPageContent = newHtmlContent
                )
            } else {
                // For other formats, use existing logic
                _uiState.value = currentState.copy(theme = newTheme)
            }
        }
    }

    fun updateScreenMetrics(screenWidth: Int, screenHeight: Int, density: Float) {
        currentScreenMetrics = ScreenMetrics(screenWidth, screenHeight, density)

        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success && !isEpubFormat) {
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
            val file = File(book.filePath)
            if (file.exists()) {
                file.readText()
            } else {
                "File not found. The book may have been moved or deleted."
            }
        } else {
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
                epubParser.parseEpubFromFile(book.filePath)
            } else {
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
            .replace(Regex("<[^>]+>"), " ")
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
            val sentences = content.split(Regex("(?<=[.!?])\\s+"))
            val pages = mutableListOf<String>()
            var currentPage = StringBuilder()
            var currentLength = 0

            for (sentence in sentences) {
                val sentenceLength = sentence.length + 1

                if (currentLength + sentenceLength > charactersPerPage && currentPage.isNotEmpty()) {
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

            if (currentPage.isNotEmpty()) {
                val pageContent = currentPage.toString().trim()
                if (pageContent.isNotEmpty()) {
                    pages.add(pageContent)
                }
            }

            return pages.ifEmpty { listOf("No content available") }

        } catch (e: Exception) {
            return splitContentSimple(content, fontSize)
        }
    }

    private fun calculateBaseCharsPerPage(): Int {
        val screenArea = currentScreenMetrics.width * currentScreenMetrics.height
        return when {
            screenArea > 2_000_000 -> 1200
            screenArea > 1_000_000 -> 1000
            else -> 800
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
            val wordLength = word.length + 1

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

    // Get reading statistics for current page
    fun getPageStatistics(): String? {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            return if (isEpubFormat && epubContent != null) {
                val totalChapters = epubContent!!.chapters.size
                val totalImages = epubContent!!.images.size
                "Chapters: $totalChapters • Images: $totalImages • Format: EPUB"
            } else {
                val currentPageContent = currentState.currentPageContent
                val words = currentPageContent.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                val chars = currentPageContent.length
                val estimatedReadingTime = kotlin.math.ceil(words.toDouble() / 200).toInt()
                "Words: $words • Characters: $chars • Est. time: ${estimatedReadingTime}min"
            }
        }
        return null
    }

    // Jump to specific page
    fun goToPage(pageNumber: Int) {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            val targetPage = pageNumber.coerceIn(1, currentState.totalPages)

            if (isEpubFormat) {
                // For EPUB, just update page counter (scrolling handled by WebView)
                _uiState.value = currentState.copy(currentPage = targetPage)
            } else {
                // For regular text, update content
                _uiState.value = currentState.copy(
                    currentPageContent = pages.getOrNull(targetPage - 1) ?: "",
                    currentPage = targetPage
                )
            }
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
            if (isEpubFormat && epubContent != null) {
                mapOf(
                    "title" to currentState.book.title,
                    "author" to currentState.book.author,
                    "format" to "EPUB",
                    "fileSize" to currentState.book.fileSize,
                    "totalChapters" to epubContent!!.chapters.size,
                    "totalImages" to epubContent!!.images.size,
                    "hasCover" to (epubContent!!.metadata.coverImageData != null),
                    "language" to (epubContent!!.metadata.language ?: "Unknown"),
                    "publisher" to (epubContent!!.metadata.publisher ?: "Unknown"),
                    "currentPage" to currentState.currentPage,
                    "totalPages" to currentState.totalPages,
                    "progress" to getReadingProgressPercentage()
                )
            } else {
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
            }
        } else null
    }

    // Get EPUB-specific information
    fun getEpubInfo(): Map<String, Any>? {
        return if (isEpubFormat && epubContent != null) {
            mapOf(
                "title" to epubContent!!.metadata.title,
                "author" to epubContent!!.metadata.author,
                "chapters" to epubContent!!.chapters.size,
                "images" to epubContent!!.images.size,
                "hasCover" to (epubContent!!.metadata.coverImageData != null),
                "language" to (epubContent!!.metadata.language ?: "Unknown"),
                "publisher" to (epubContent!!.metadata.publisher ?: "Unknown"),
                "description" to (epubContent!!.metadata.description ?: "No description"),
                "tableOfContents" to epubContent!!.tableOfContents.map {
                    mapOf("title" to it.title, "href" to it.href)
                },
                "chapterTitles" to epubContent!!.chapters.map { it.title }
            )
        } else null
    }

    // Get chapter navigation for EPUB
    fun getEpubChapters(): List<String> {
        return if (isEpubFormat && epubContent != null) {
            listOf("Cover") + epubContent!!.chapters.map { it.title }
        } else emptyList()
    }

    // Jump to specific chapter (EPUB only)
    fun goToChapter(chapterIndex: Int) {
        if (isEpubFormat && epubContent != null) {
            val targetPage = chapterIndex + 1 // +1 for cover page
            goToPage(targetPage)
        }
    }

    // Search within book content
    fun searchInBook(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        if (isEpubFormat && epubContent != null) {
            // Search in EPUB chapters
            epubContent!!.chapters.forEachIndexed { chapterIndex, chapter ->
                val content = chapter.content.lowercase()
                val queryLower = query.lowercase()
                var startIndex = 0

                while (true) {
                    val index = content.indexOf(queryLower, startIndex)
                    if (index == -1) break

                    // Get context around the match
                    val contextStart = (index - 50).coerceAtLeast(0)
                    val contextEnd = (index + query.length + 50).coerceAtMost(content.length)
                    val context = content.substring(contextStart, contextEnd)

                    results.add(
                        SearchResult(
                            chapterTitle = chapter.title,
                            chapterIndex = chapterIndex,
                            pageNumber = chapterIndex + 2, // +2 for cover and 1-based indexing
                            context = context,
                            matchIndex = index - contextStart
                        )
                    )

                    startIndex = index + 1
                    if (results.size >= 50) break // Limit results
                }
            }
        } else {
            // Search in regular text pages
            pages.forEachIndexed { pageIndex, pageContent ->
                val content = pageContent.lowercase()
                val queryLower = query.lowercase()
                var startIndex = 0

                while (true) {
                    val index = content.indexOf(queryLower, startIndex)
                    if (index == -1) break

                    val contextStart = (index - 50).coerceAtLeast(0)
                    val contextEnd = (index + query.length + 50).coerceAtMost(content.length)
                    val context = content.substring(contextStart, contextEnd)

                    results.add(
                        SearchResult(
                            chapterTitle = "Page ${pageIndex + 1}",
                            chapterIndex = pageIndex,
                            pageNumber = pageIndex + 1,
                            context = context,
                            matchIndex = index - contextStart
                        )
                    )

                    startIndex = index + 1
                    if (results.size >= 50) break
                }
            }
        }

        return results
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

    // Toggle bookmark for current position
    fun toggleBookmark() {
        val currentState = _uiState.value
        if (currentState is ReadingUiState.Success) {
            // TODO: Implement bookmark functionality
            Log.d("ReadingViewModel", "Bookmark toggled for page ${currentState.currentPage}")
        }
    }

    // Get reading session statistics
    fun getReadingSessionStats(): Map<String, Any> {
        val currentState = _uiState.value
        return if (currentState is ReadingUiState.Success) {
            val startTime = System.currentTimeMillis() - (60 * 1000) // Placeholder: 1 minute ago
            val sessionDuration = System.currentTimeMillis() - startTime
            val pagesRead = 5 // Placeholder

            mapOf(
                "sessionDuration" to sessionDuration,
                "pagesRead" to pagesRead,
                "currentPage" to currentState.currentPage,
                "totalPages" to currentState.totalPages,
                "progressPercentage" to getReadingProgressPercentage(),
                "estimatedTimeLeft" to estimateTimeLeft(currentState)
            )
        } else emptyMap()
    }

    private fun estimateTimeLeft(state: ReadingUiState.Success): Int {
        val pagesLeft = state.totalPages - state.currentPage
        val averageWordsPerPage = if (isEpubFormat) 300 else 250 // Estimate
        val wordsLeft = pagesLeft * averageWordsPerPage
        return (wordsLeft / 200) // 200 words per minute reading speed
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up resources
        currentBook = null
        bookContent = ""
        pages = emptyList()
        epubContent = null
        isEpubFormat = false
        Log.d("ReadingViewModel", "ViewModel cleared and resources cleaned up")
    }
}

// Data class for search results
data class SearchResult(
    val chapterTitle: String,
    val chapterIndex: Int,
    val pageNumber: Int,
    val context: String,
    val matchIndex: Int
)