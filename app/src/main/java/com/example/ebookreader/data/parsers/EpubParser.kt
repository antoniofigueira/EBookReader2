package com.example.ebookreader.data.parser

import android.content.Context
import android.net.Uri
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
    val coverPath: String? = null
)

data class EpubChapter(
    val id: String,
    val title: String,
    val content: String,
    val order: Int
)

data class EpubContent(
    val metadata: EpubMetadata,
    val chapters: List<EpubChapter>,
    val fullText: String
)

@Singleton
class EpubParser @Inject constructor() {

    suspend fun parseEpub(context: Context, uri: Uri): EpubContent? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            parseEpubFromStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun parseEpubFromFile(filePath: String): EpubContent? = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) return@withContext null
            parseEpubFromStream(file.inputStream())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun parseEpubFromStream(inputStream: InputStream): EpubContent? {
        val zipEntries = mutableMapOf<String, ByteArray>()

        // Read all ZIP entries
        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    zipEntries[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }

        // Find container.xml to locate content.opf
        val containerXml = zipEntries["META-INF/container.xml"]?.let { String(it) }
        val contentOpfPath = extractContentOpfPath(containerXml) ?: return null

        // Parse content.opf for metadata and spine
        val contentOpf = zipEntries[contentOpfPath]?.let { String(it) } ?: return null
        val metadata = parseMetadata(contentOpf)
        val manifest = parseManifest(contentOpf)
        val spine = parseSpine(contentOpf)

        // Extract chapters in order
        val chapters = mutableListOf<EpubChapter>()
        spine.forEachIndexed { index, spineItem ->
            val manifestItem = manifest[spineItem.idref]
            if (manifestItem != null) {
                val chapterPath = resolveHref(contentOpfPath, manifestItem.href)
                val chapterContent = zipEntries[chapterPath]?.let { String(it) }
                if (chapterContent != null) {
                    val cleanContent = extractTextFromHtml(chapterContent)
                    chapters.add(
                        EpubChapter(
                            id = spineItem.idref,
                            title = manifestItem.title ?: "Chapter ${index + 1}",
                            content = cleanContent,
                            order = index
                        )
                    )
                }
            }
        }

        val fullText = chapters.joinToString("\n\n") { "${it.title}\n\n${it.content}" }

        return EpubContent(
            metadata = metadata,
            chapters = chapters,
            fullText = fullText
        )
    }

    fun extractContentOpfPath(containerXml: String?): String? {
        if (containerXml == null) return null
        val regex = """full-path="([^"]+)"""".toRegex()
        return regex.find(containerXml)?.groupValues?.get(1)
    }

    fun parseMetadata(contentOpf: String): EpubMetadata {
        val titleRegex = """<dc:title[^>]*>([^<]+)</dc:title>""".toRegex()
        val authorRegex = """<dc:creator[^>]*>([^<]+)</dc:creator>""".toRegex()
        val descriptionRegex = """<dc:description[^>]*>([^<]+)</dc:description>""".toRegex()
        val languageRegex = """<dc:language[^>]*>([^<]+)</dc:language>""".toRegex()
        val publisherRegex = """<dc:publisher[^>]*>([^<]+)</dc:publisher>""".toRegex()

        return EpubMetadata(
            title = titleRegex.find(contentOpf)?.groupValues?.get(1)?.trim() ?: "Unknown Title",
            author = authorRegex.find(contentOpf)?.groupValues?.get(1)?.trim() ?: "Unknown Author",
            description = descriptionRegex.find(contentOpf)?.groupValues?.get(1)?.trim(),
            language = languageRegex.find(contentOpf)?.groupValues?.get(1)?.trim(),
            publisher = publisherRegex.find(contentOpf)?.groupValues?.get(1)?.trim()
        )
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
        val manifestRegex = """<item\s+id="([^"]+)"\s+href="([^"]+)"\s+media-type="([^"]+)"[^>]*/>""".toRegex()
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
        val spineRegex = """<itemref\s+idref="([^"]+)"[^>]*/>""".toRegex()
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
}

// Extension function to integrate with existing ViewModel
fun EpubParser.extractMetadataForLibrary(
    context: Context,
    uri: Uri
): Pair<String, String>? {
    return try {
        // Quick metadata extraction without full parsing
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
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
        val contentOpfPath = extractContentOpfPath(containerXml) ?: return null
        val contentOpf = zipEntries[contentOpfPath]?.let { String(it) } ?: return null

        val metadata = parseMetadata(contentOpf)
        Pair(metadata.title, metadata.author)
    } catch (e: Exception) {
        null
    }
}