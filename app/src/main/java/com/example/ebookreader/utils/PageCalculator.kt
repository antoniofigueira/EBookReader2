package com.example.ebookreader.utils

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

data class PageConfiguration(
    val screenWidth: Int,
    val screenHeight: Int,
    val fontSize: Float,
    val lineHeight: Float,
    val marginHorizontal: Int,
    val marginVertical: Int,
    val density: Float
)

data class PageContent(
    val pageNumber: Int,
    val content: String,
    val wordCount: Int,
    val characterCount: Int
)

data class PaginationResult(
    val pages: List<PageContent>,
    val totalPages: Int,
    val totalWords: Int,
    val configuration: PageConfiguration
)

class PageCalculator(
    private val context: Context,
    private val density: Density
) {

    // Cache for calculated pages
    private val pageCache = ConcurrentHashMap<String, PaginationResult>()

    // Text measurement paint
    private var measurePaint: Paint? = null

    suspend fun calculatePages(
        content: String,
        config: PageConfiguration
    ): PaginationResult = withContext(Dispatchers.Default) {

        // Create cache key
        val cacheKey = createCacheKey(content, config)

        // Check cache first
        pageCache[cacheKey]?.let { cachedResult ->
            return@withContext cachedResult
        }

        // Initialize text measurement
        val paint = getOrCreatePaint(config)

        // Calculate layout dimensions
        val layoutInfo = calculateLayoutDimensions(config)

        // Split content into pages
        val pages = splitContentIntoPages(content, paint, layoutInfo, config)

        // Create result
        val result = PaginationResult(
            pages = pages,
            totalPages = pages.size,
            totalWords = pages.sumOf { it.wordCount },
            configuration = config
        )

        // Cache result
        pageCache[cacheKey] = result

        result
    }

    private fun createCacheKey(content: String, config: PageConfiguration): String {
        return "${content.hashCode()}_${config.hashCode()}"
    }

    private fun getOrCreatePaint(config: PageConfiguration): Paint {
        return measurePaint?.apply {
            textSize = config.fontSize * config.density
        } ?: Paint().apply {
            textSize = config.fontSize * config.density
            isAntiAlias = true
            measurePaint = this
        }
    }

    private data class LayoutInfo(
        val availableWidth: Int,
        val availableHeight: Int,
        val lineHeight: Float,
        val linesPerPage: Int,
        val charsPerLine: Int
    )

    private fun calculateLayoutDimensions(config: PageConfiguration): LayoutInfo {
        val availableWidth = config.screenWidth - (config.marginHorizontal * 2)
        val availableHeight = config.screenHeight - (config.marginVertical * 2)

        val lineHeight = config.lineHeight
        val linesPerPage = (availableHeight / lineHeight).toInt()

        // Estimate characters per line (will be refined with actual measurement)
        val averageCharWidth = config.fontSize * 0.6f * config.density
        val charsPerLine = (availableWidth / averageCharWidth).toInt()

        return LayoutInfo(
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            lineHeight = lineHeight,
            linesPerPage = linesPerPage,
            charsPerLine = charsPerLine
        )
    }

    private fun splitContentIntoPages(
        content: String,
        paint: Paint,
        layoutInfo: LayoutInfo,
        config: PageConfiguration
    ): List<PageContent> {

        val pages = mutableListOf<PageContent>()
        val paragraphs = content.split("\n\n", "\n")

        var currentPageContent = StringBuilder()
        var currentPageLines = 0
        var pageNumber = 1

        for (paragraph in paragraphs) {
            val paragraphLines = wrapParagraphToLines(paragraph, paint, layoutInfo.availableWidth)

            // Check if current paragraph fits on current page
            if (currentPageLines + paragraphLines.size > layoutInfo.linesPerPage && currentPageContent.isNotEmpty()) {
                // Create page with current content
                val pageText = currentPageContent.toString().trim()
                if (pageText.isNotEmpty()) {
                    pages.add(createPageContent(pageNumber, pageText))
                    pageNumber++
                }

                // Start new page
                currentPageContent = StringBuilder()
                currentPageLines = 0
            }

            // Add paragraph lines to current page
            for (line in paragraphLines) {
                if (currentPageLines >= layoutInfo.linesPerPage) {
                    // Page is full, create new page
                    val pageText = currentPageContent.toString().trim()
                    if (pageText.isNotEmpty()) {
                        pages.add(createPageContent(pageNumber, pageText))
                        pageNumber++
                    }

                    currentPageContent = StringBuilder()
                    currentPageLines = 0
                }

                currentPageContent.append(line).append(" ")
                currentPageLines++

                // Add spacing between paragraphs
                if (line == paragraphLines.last() && paragraphLines.size > 1) {
                    currentPageContent.append("\n")
                    currentPageLines++
                }
            }
        }

        // Add final page if it has content
        if (currentPageContent.isNotEmpty()) {
            val pageText = currentPageContent.toString().trim()
            if (pageText.isNotEmpty()) {
                pages.add(createPageContent(pageNumber, pageText))
            }
        }

        return pages.ifEmpty {
            listOf(createPageContent(1, "No content available"))
        }
    }

    private fun wrapParagraphToLines(paragraph: String, paint: Paint, maxWidth: Int): List<String> {
        if (paragraph.isBlank()) return listOf("")

        val words = paragraph.split("\\s+".toRegex())
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) {
                word
            } else {
                "${currentLine} $word"
            }

            val textBounds = Rect()
            paint.getTextBounds(testLine, 0, testLine.length, textBounds)

            if (textBounds.width() <= maxWidth || currentLine.isEmpty()) {
                currentLine = StringBuilder(testLine)
            } else {
                // Current line is full, start new line
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }

        // Add the last line
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines.ifEmpty { listOf("") }
    }

    private fun createPageContent(pageNumber: Int, content: String): PageContent {
        val words = content.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return PageContent(
            pageNumber = pageNumber,
            content = content,
            wordCount = words.size,
            characterCount = content.length
        )
    }

    // Cache management
    fun clearCache() {
        pageCache.clear()
        measurePaint = null
    }

    fun getCacheSize(): Int = pageCache.size

    // Utility function to estimate reading time
    fun estimateReadingTime(wordCount: Int, wordsPerMinute: Int = 200): Int {
        return ceil(wordCount.toDouble() / wordsPerMinute).toInt()
    }
}

// Factory object to create PageConfiguration instances
object PageConfigurationFactory {
    fun fromScreenMetrics(
        screenWidth: Int,
        screenHeight: Int,
        fontSize: Float,
        density: Float,
        marginDp: Int = 16
    ): PageConfiguration {
        val marginPx = (marginDp * density).toInt()
        val lineHeight = fontSize * 1.4f * density

        return PageConfiguration(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            fontSize = fontSize,
            lineHeight = lineHeight,
            marginHorizontal = marginPx,
            marginVertical = marginPx,
            density = density
        )
    }

    fun createDefault(density: Float = 3f): PageConfiguration {
        return fromScreenMetrics(
            screenWidth = 1080,
            screenHeight = 1920,
            fontSize = 16f,
            density = density
        )
    }
}