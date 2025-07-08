package com.example.ebookreader.presentation.ui.reader

import androidx.compose.ui.graphics.Color

sealed interface ReadingUiState {
    object Loading : ReadingUiState
    data class Error(val message: String) : ReadingUiState
    data class Success(
        val book: com.example.ebookreader.domain.model.Book,
        val currentPageContent: String,
        val currentPage: Int,
        val totalPages: Int,
        val fontSize: Float = 16f,
        val theme: ReadingTheme = ReadingTheme.LIGHT
    ) : ReadingUiState
}

enum class ReadingTheme(
    val displayName: String,
    val backgroundColor: Color,
    val textColor: Color,
    val surfaceColor: Color
) {
    LIGHT(
        displayName = "Light",
        backgroundColor = Color(0xFFFFFFFF),
        textColor = Color(0xFF000000),
        surfaceColor = Color(0xFFF5F5F5)
    ),
    DARK(
        displayName = "Dark",
        backgroundColor = Color(0xFF121212),
        textColor = Color(0xFFE0E0E0),
        surfaceColor = Color(0xFF1E1E1E)
    ),
    SEPIA(
        displayName = "Sepia",
        backgroundColor = Color(0xFFF4F1EA),
        textColor = Color(0xFF5D4E37),
        surfaceColor = Color(0xFFEAE4D3)
    ),
    BLACK(
        displayName = "Black",
        backgroundColor = Color(0xFF000000),
        textColor = Color(0xFFE0E0E0),
        surfaceColor = Color(0xFF0D0D0D)
    )
}