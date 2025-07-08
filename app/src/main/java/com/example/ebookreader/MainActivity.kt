package com.example.ebookreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.ebookreader.presentation.ui.library.LibraryScreen
import com.example.ebookreader.presentation.ui.reader.ReadingScreen
import com.example.ebookreader.ui.theme.EBookReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EBookReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EBookReaderApp()
                }
            }
        }
    }

    // Grant persistent URI permissions
    fun grantUriPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun EBookReaderApp() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Library) }

    when (val screen = currentScreen) {
        is Screen.Library -> {
            LibraryScreen(
                onBookClick = { book ->
                    currentScreen = Screen.Reading(book.id)
                }
            )
        }
        is Screen.Reading -> {
            ReadingScreen(
                bookId = screen.bookId,
                onBackPressed = {
                    currentScreen = Screen.Library
                }
            )
        }
    }
}

sealed class Screen {
    object Library : Screen()
    data class Reading(val bookId: String) : Screen()
}