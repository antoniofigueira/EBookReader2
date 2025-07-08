package com.example.ebookreader.data.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class EpubMetadata(
    val title: String,
    val author: String,
    val description: String? = null,
    val language: String? = null,
    val publisher: String? = null,
    val coverImageData: ByteArray? = null,
    val coverPath: String? = null
)

data class EpubImage(
    val id: String,
    val path: String,
    val mimeType: String,
    val data: ByteArray
)

data class EpubChapter(
    val id: String,
    val title: String,
    val content: String,
    val htmlContent: String, // Keep original HTML for formatting
    val order: Int,
    val images: List<EpubImage> = emptyList()
)

data class EpubContent(
    val metadata: EpubMetadata,
    val chapters: List<EpubChapter>,
    val images: Map<String, EpubImage>,
    val fullText: String,
    val fullHtmlContent: String, // Complete HTML with proper formatting
    val tableOfContents: List<TocEntry> = emptyList()
)

data class TocEntry(
    val title: String,
    val href: String,
    val chapterIndex: Int
)

@Singleton
class EpubParser @Inject constructor() {

    companion object {
        private const val TAG = "EpubParser"
    }

    suspend fun parseEpub(context: Context, uri: Uri): EpubContent? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting enhanced EPUB parsing for URI: $uri")
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            parseEpubFromStream(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPUB from URI", e)
            null
        }
    }

    suspend fun parseEpubFromFile(filePath: String): EpubContent? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting enhanced EPUB parsing for file: $filePath")
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: $filePath")
                return@withContext null
            }
            parseEpubFromStream(file.inputStream())
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPUB from file", e)
            null
        }
    }

    private suspend fun parseEpubFromStream(inputStream: InputStream): EpubContent? {
        val zipEntries = mutableMapOf<String, ByteArray>()

        Log.d(TAG, "Reading ZIP entries...")

        // Read all ZIP entries
        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    zipEntries[entry.name] = zip.readBytes()
                    Log.d(TAG, "Read entry: ${entry.name} (${zipEntries[entry.name]?.size} bytes)")
                }
                entry = zip.nextEntry
            }
        }

        // Find container.xml to locate content.opf
        val containerXml = zipEntries["META-INF/container.xml"]?.let { String(it) }
        val contentOpfPath = extractContentOpfPath(containerXml) ?: return null

        // Parse content.opf for metadata and spine
        val contentOpf = zipEntries[contentOpfPath]?.let { String(it) } ?: return null

        val metadata = parseMetadata(contentOpf, zipEntries)
        val manifest = parseManifest(contentOpf)
        val spine = parseSpine(contentOpf)

        // Extract all images first
        val images = extractImages(zipEntries, manifest, contentOpfPath)

        // Parse table of contents
        val toc = parseTableOfContents(zipEntries, manifest, contentOpfPath)

        // Extract chapters with proper formatting and images
        val chapters = mutableListOf<EpubChapter>()
        spine.forEachIndexed { index, spineItem ->
            val manifestItem = manifest[spineItem.idref]
            if (manifestItem != null && manifestItem.mediaType.contains("html")) {
                val chapterPath = resolveHref(contentOpfPath, manifestItem.href)
                val chapterContent = zipEntries[chapterPath]?.let { String(it) }

                if (chapterContent != null) {
                    // Get chapter title from TOC or generate one
                    val chapterTitle = getChapterTitle(toc, manifestItem.href, index)

                    // Process HTML content with images and formatting
                    val processedHtml = processHtmlContent(chapterContent, images, contentOpfPath)
                    val cleanText = extractTextFromHtml(chapterContent)

                    if (cleanText.isNotBlank()) {
                        chapters.add(
                            EpubChapter(
                                id = spineItem.idref,
                                title = chapterTitle,
                                content = cleanText,
                                htmlContent = processedHtml,
                                order = index,
                                images = images.values.toList()
                            )
                        )
                    }
                }
            }
        }

        // Create full HTML content with proper styling
        val fullHtmlContent = createFullHtmlContent(chapters, metadata)
        val fullText = chapters.joinToString("\n\n") { "${it.title}\n\n${it.content}" }

        return EpubContent(
            metadata = metadata,
            chapters = chapters,
            images = images,
            fullText = fullText,
            fullHtmlContent = fullHtmlContent,
            tableOfContents = toc
        )
    }

    private fun parseMetadata(contentOpf: String, zipEntries: Map<String, ByteArray>): EpubMetadata {
        val titleRegex = """<dc:title[^>]*>([^<]+)</dc:title>""".toRegex(RegexOption.IGNORE_CASE)
        val authorRegex = """<dc:creator[^>]*>([^<]+)</dc:creator>""".toRegex(RegexOption.IGNORE_CASE)
        val descriptionRegex = """<dc:description[^>]*>([^<]+)</dc:description>""".toRegex(RegexOption.IGNORE_CASE)
        val languageRegex = """<dc:language[^>]*>([^<]+)</dc:language>""".toRegex(RegexOption.IGNORE_CASE)
        val publisherRegex = """<dc:publisher[^>]*>([^<]+)</dc:publisher>""".toRegex(RegexOption.IGNORE_CASE)

        // Extract cover image
        val coverImageData = extractCoverImage(contentOpf, zipEntries)

        return EpubMetadata(
            title = titleRegex.find(contentOpf)?.groupValues?.get(1)?.trim() ?: "Unknown Title",
            author = authorRegex.find(contentOpf)?.groupValues?.get(1)?.trim() ?: "Unknown Author",
            description = descriptionRegex.find(contentOpf)?.groupValues?.get(1)?.trim(),
            language = languageRegex.find(contentOpf)?.groupValues?.get(1)?.trim(),
            publisher = publisherRegex.find(contentOpf)?.groupValues?.get(1)?.trim(),
            coverImageData = coverImageData
        )
    }

    private fun extractCoverImage(contentOpf: String, zipEntries: Map<String, ByteArray>): ByteArray? {
        try {
            // Look for cover in metadata
            val coverRegex = """<meta\s+name="cover"\s+content="([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
            val coverId = coverRegex.find(contentOpf)?.groupValues?.get(1)

            if (coverId != null) {
                // Find the cover item in manifest
                val itemRegex = """<item\s+[^>]*id="$coverId"[^>]*href="([^"]+)"[^>]*/>""".toRegex(RegexOption.IGNORE_CASE)
                val coverHref = itemRegex.find(contentOpf)?.groupValues?.get(1)

                if (coverHref != null) {
                    val coverPath = if (coverHref.contains("/")) coverHref else "OEBPS/$coverHref"
                    return zipEntries[coverPath] ?: zipEntries["OEBPS/Images/$coverHref"]
                }
            }

            // Alternative: look for common cover file names
            val commonCoverNames = listOf(
                "cover.jpg", "cover.jpeg", "cover.png",
                "OEBPS/Images/cover.jpg", "OEBPS/Images/cover.jpeg", "OEBPS/Images/cover.png",
                "Images/cover.jpg", "Images/cover.jpeg", "Images/cover.png"
            )

            for (coverName in commonCoverNames) {
                zipEntries[coverName]?.let { return it }
            }

            // Last resort: find first image that might be cover
            val imageFiles = zipEntries.keys.filter {
                it.lowercase().contains("cover") &&
                        (it.lowercase().endsWith(".jpg") || it.lowercase().endsWith(".jpeg") || it.lowercase().endsWith(".png"))
            }

            return imageFiles.firstOrNull()?.let { zipEntries[it] }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting cover image", e)
            return null
        }
    }

    private fun extractImages(
        zipEntries: Map<String, ByteArray>,
        manifest: Map<String, ManifestItem>,
        contentOpfPath: String
    ): Map<String, EpubImage> {
        val images = mutableMapOf<String, EpubImage>()

        manifest.values.forEach { item ->
            if (item.mediaType.startsWith("image/")) {
                val imagePath = resolveHref(contentOpfPath, item.href)
                val imageData = zipEntries[imagePath]

                if (imageData != null) {
                    images[item.href] = EpubImage(
                        id = item.id,
                        path = item.href,
                        mimeType = item.mediaType,
                        data = imageData
                    )
                }
            }
        }

        Log.d(TAG, "Extracted ${images.size} images")
        return images
    }

    private fun parseTableOfContents(
        zipEntries: Map<String, ByteArray>,
        manifest: Map<String, ManifestItem>,
        contentOpfPath: String
    ): List<TocEntry> {
        try {
            // Look for NCX file (EPUB 2) or Navigation Document (EPUB 3)
            val ncxItem = manifest.values.find { it.mediaType == "application/x-dtbncx+xml" }
            val navItem = manifest.values.find { it.mediaType == "application/xhtml+xml" && it.id.contains("nav") }

            val tocFile = ncxItem ?: navItem
            if (tocFile != null) {
                val tocPath = resolveHref(contentOpfPath, tocFile.href)
                val tocContent = zipEntries[tocPath]?.let { String(it) }

                if (tocContent != null) {
                    return if (tocFile.mediaType == "application/x-dtbncx+xml") {
                        parseNcxToc(tocContent)
                    } else {
                        parseNavToc(tocContent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing table of contents", e)
        }

        return emptyList()
    }

    private fun parseNcxToc(ncxContent: String): List<TocEntry> {
        val tocEntries = mutableListOf<TocEntry>()
        val navPointRegex = """<navPoint[^>]*>.*?<navLabel>.*?<text>([^<]+)</text>.*?<content src="([^"]+)"[^>]*/>.*?</navPoint>""".toRegex(RegexOption.DOT_MATCHES_ALL)

        navPointRegex.findAll(ncxContent).forEachIndexed { index, match ->
            val title = match.groupValues[1].trim()
            val href = match.groupValues[2].trim()
            tocEntries.add(TocEntry(title, href, index))
        }

        return tocEntries
    }

    private fun parseNavToc(navContent: String): List<TocEntry> {
        val tocEntries = mutableListOf<TocEntry>()
        val linkRegex = """<a[^>]+href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()

        linkRegex.findAll(navContent).forEachIndexed { index, match ->
            val href = match.groupValues[1].trim()
            val title = match.groupValues[2].trim()
            tocEntries.add(TocEntry(title, href, index))
        }

        return tocEntries
    }

    private fun getChapterTitle(toc: List<TocEntry>, href: String, defaultIndex: Int): String {
        // Remove fragment identifier if present
        val cleanHref = href.split("#")[0]

        val tocEntry = toc.find { it.href.split("#")[0] == cleanHref }
        return tocEntry?.title?.takeIf { it.isNotBlank() && !it.matches(Regex("part\\d+.*", RegexOption.IGNORE_CASE)) }
            ?: "Chapter ${defaultIndex + 1}"
    }

    private fun processHtmlContent(
        htmlContent: String,
        images: Map<String, EpubImage>,
        contentOpfPath: String
    ): String {
        var processedHtml = htmlContent

        // Replace image sources with data URIs
        val imgRegex = """<img[^>]+src="([^"]+)"[^>]*>""".toRegex()
        processedHtml = imgRegex.replace(processedHtml) { matchResult ->
            val imageSrc = matchResult.groupValues[1]
            val image = images[imageSrc]

            if (image != null) {
                val base64Data = android.util.Base64.encodeToString(image.data, android.util.Base64.DEFAULT)
                matchResult.value.replace(imageSrc, "data:${image.mimeType};base64,$base64Data")
            } else {
                matchResult.value
            }
        }

        return processedHtml
    }

    private fun createFullHtmlContent(chapters: List<EpubChapter>, metadata: EpubMetadata): String {
        val styleSheet = """
            <style>
                body {
                    font-family: 'Roboto', Georgia, serif;
                    line-height: 1.6;
                    margin: 0;
                    padding: 16px;
                    max-width: 100%;
                }
                .cover-page {
                    text-align: center;
                    padding: 40px 20px;
                    page-break-after: always;
                }
                .cover-image {
                    max-width: 80%;
                    height: auto;
                    margin-bottom: 20px;
                }
                .title {
                    font-size: 2em;
                    font-weight: bold;
                    margin: 20px 0;
                    color: #333;
                }
                .author {
                    font-size: 1.2em;
                    color: #666;
                    margin-bottom: 10px;
                }
                .chapter {
                    page-break-before: always;
                    margin-bottom: 30px;
                }
                .chapter-title {
                    font-size: 1.5em;
                    font-weight: bold;
                    margin: 30px 0 20px 0;
                    color: #333;
                    border-bottom: 2px solid #eee;
                    padding-bottom: 10px;
                }
                h1, h2, h3, h4, h5, h6 {
                    color: #333;
                    margin-top: 1.5em;
                    margin-bottom: 0.5em;
                }
                p {
                    text-align: justify;
                    margin-bottom: 1em;
                }
                img {
                    max-width: 100%;
                    height: auto;
                    display: block;
                    margin: 20px auto;
                }
                blockquote {
                    border-left: 4px solid #ddd;
                    margin: 1em 0;
                    padding-left: 1em;
                    font-style: italic;
                }
                ul, ol {
                    margin: 1em 0;
                    padding-left: 2em;
                }
                li {
                    margin-bottom: 0.5em;
                }
            </style>
        """

        val coverPage = if (metadata.coverImageData != null) {
            val base64Cover = android.util.Base64.encodeToString(metadata.coverImageData, android.util.Base64.DEFAULT)
            """
                <div class="cover-page">
                    <img src="data:image/jpeg;base64,$base64Cover" class="cover-image" alt="Cover" />
                    <div class="title">${metadata.title}</div>
                    <div class="author">by ${metadata.author}</div>
                </div>
            """
        } else {
            """
                <div class="cover-page">
                    <div class="title">${metadata.title}</div>
                    <div class="author">by ${metadata.author}</div>
                </div>
            """
        }

        val chaptersHtml = chapters.joinToString("\n") { chapter ->
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
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${metadata.title}</title>
                $styleSheet
            </head>
            <body>
                $coverPage
                $chaptersHtml
            </body>
            </html>
        """
    }

    // Keep existing helper functions
    private fun extractContentOpfPath(containerXml: String?): String? {
        if (containerXml == null) return null
        val regex = """full-path="([^"]+)"""".toRegex()
        return regex.find(containerXml)?.groupValues?.get(1)
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val title: String? = null
    )

    private data class SpineItem(
        val idref: String,
        val linear: Boolean = true
    )

    private fun parseManifest(contentOpf: String): Map<String, ManifestItem> {
        val manifestRegex = """<item\s+[^>]*id="([^"]+)"[^>]*href="([^"]+)"[^>]*media-type="([^"]+)"[^>]*/>""".toRegex(RegexOption.IGNORE_CASE)
        val items = mutableMapOf<String, ManifestItem>()

        manifestRegex.findAll(contentOpf).forEach { match ->
            val id = match.groupValues[1]
            val href = match.groupValues[2]
            val mediaType = match.groupValues[3]

            items[id] = ManifestItem(id, href, mediaType)
        }

        return items
    }

    private fun parseSpine(contentOpf: String): List<SpineItem> {
        val spineRegex = """<itemref\s+[^>]*idref="([^"]+)"[^>]*/>""".toRegex(RegexOption.IGNORE_CASE)
        return spineRegex.findAll(contentOpf).map { match ->
            SpineItem(idref = match.groupValues[1])
        }.toList()
    }

    private fun resolveHref(basePath: String, href: String): String {
        val baseDir = basePath.substringBeforeLast('/')
        return if (baseDir.isEmpty()) href else "$baseDir/$href"
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

    // Extension function for quick metadata extraction
    suspend fun extractMetadataForLibrary(
        context: Context,
        uri: Uri
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val zipEntries = mutableMapOf<String, ByteArray>()

            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "META-INF/container.xml" || entry.name.endsWith(".opf")) {
                        zipEntries[entry.name] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }

            val containerXml = zipEntries["META-INF/container.xml"]?.let { String(it) }
            val contentOpfPath = extractContentOpfPath(containerXml) ?: return@withContext null
            val contentOpf = zipEntries[contentOpfPath]?.let { String(it) } ?: return@withContext null

            val metadata = parseMetadata(contentOpf, zipEntries)
            Pair(metadata.title, metadata.author)
        } catch (e: Exception) {
            Log.e(TAG, "Error in quick metadata extraction", e)
            null
        }
    }
}