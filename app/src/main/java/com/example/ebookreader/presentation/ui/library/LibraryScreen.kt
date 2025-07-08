@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.ebookreader.presentation.ui.library

import java.io.File
import androidx.compose.foundation.combinedClickable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.ebookreader.domain.model.Book
import com.example.ebookreader.presentation.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onBookClick: (com.example.ebookreader.domain.model.Book) -> Unit = {} // Add this parameter
) {
    val books by viewModel.books.collectAsState()

    // File picker launcher with format filtering
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importBookFromUri(it) }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top App Bar
        TopAppBar(
            title = { Text("My Library") },
            actions = {
                IconButton(onClick = { /* TODO: Search */ }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
                IconButton(onClick = {
                    // Launch file picker with supported formats
                    filePickerLauncher.launch("*/*") // We'll filter by text and ebook formats
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Import Book")
                }
            }
        )

        // Rest of your existing LibraryScreen content stays the same...
        if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No books yet",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap + to import your first book",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(books) { book ->
                    BookItem(
                        book = book,
                        onClick = { onBookClick(book) } // Update this line
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookItem(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {} // Add long-click parameter
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        //onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Book cover placeholder
            Card(
                modifier = Modifier
                    .width(60.dp)
                    .height(80.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = book.format.extension.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Book info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // File size info
                Text(
                    text = formatFileSize(book.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Progress bar
                if (book.readingProgress > 0) {
                    LinearProgressIndicator(
                        progress = book.readingProgress,
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

// Helper function to format file size
fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }
}