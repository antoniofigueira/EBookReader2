package com.example.ebookreader.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.ebookreader.domain.model.Book
import com.example.ebookreader.domain.model.BookFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileImportManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val supportedFormats = listOf("txt", "epub", "pdf", "mobi")

    fun isSupportedFile(uri: Uri): Boolean {
        val documentFile = DocumentFile.fromSingleUri(context, uri)
        val fileName = documentFile?.name ?: return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in supportedFormats
    }

    suspend fun importBook(uri: Uri): Book? {
        try {
            val documentFile = DocumentFile.fromSingleUri(context, uri) ?: return null
            val fileName = documentFile.name ?: return null
            val fileSize = documentFile.length()

            val (title, author) = extractMetadataFromFilename(fileName)
            val format = getBookFormat(fileName)

            return Book(
                id = UUID.randomUUID().toString(),
                title = title,
                author = author,
                filePath = uri.toString(),
                fileUri = uri.toString(),
                format = format,
                fileSize = fileSize,
                totalPages = 0,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun extractMetadataFromFilename(fileName: String): Pair<String, String> {
        val nameWithoutExtension = fileName.substringBeforeLast('.')

        if (nameWithoutExtension.contains(" - ")) {
            val parts = nameWithoutExtension.split(" - ", limit = 2)
            return Pair(parts[1].trim(), parts[0].trim())
        }

        if (nameWithoutExtension.contains(" by ")) {
            val parts = nameWithoutExtension.split(" by ", limit = 2)
            return Pair(parts[0].trim(), parts[1].trim())
        }

        return Pair(nameWithoutExtension, "Unknown Author")
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

    fun readFileContent(uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}