package com.example.ebookreader.presentation.ui.reader

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.ebookreader.presentation.viewmodel.ReadingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    bookId: String,
    onBackPressed: () -> Unit,
    viewModel: ReadingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            if (uiState is ReadingUiState.Success) {
                                viewModel.previousPage()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            if (uiState is ReadingUiState.Success) {
                                viewModel.nextPage()
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        when (val state = uiState) {
            is ReadingUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is ReadingUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Error loading book",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBackPressed) {
                            Text("Back to Library")
                        }
                    }
                }
            }

            is ReadingUiState.Success -> {
                ReadingContent(
                    state = state,
                    onBackPressed = onBackPressed,
                    onPreviousPage = { viewModel.previousPage() },
                    onNextPage = { viewModel.nextPage() },
                    onFontSizeChange = { viewModel.changeFontSize(it) },
                    onThemeChange = { viewModel.changeTheme(it) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingContent(
    state: ReadingUiState.Success,
    onBackPressed: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onThemeChange: (ReadingTheme) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar with theme-aware colors
            TopAppBar(
                title = {
                    Text(
                        text = state.book.title,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = state.theme.surfaceColor,
                    titleContentColor = state.theme.textColor,
                    navigationIconContentColor = state.theme.textColor,
                    actionIconContentColor = state.theme.textColor
                )
            )

            // Main reading area with theme background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .background(state.theme.backgroundColor)
            ) {
                // Full-width text content with theme colors
                Text(
                    text = state.currentPageContent,
                    fontSize = state.fontSize.sp,
                    lineHeight = (state.fontSize * 1.4).sp,
                    color = state.theme.textColor,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    textAlign = TextAlign.Justify
                )

                // Invisible tap zones (overlay layer)
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Left tap zone (Previous page) - invisible
                    Box(
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (state.currentPage > 1) {
                                    onPreviousPage()
                                }
                            }
                    )

                    // Center tap zone (Show/hide controls) - invisible
                    Box(
                        modifier = Modifier
                            .weight(0.4f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                showControls = !showControls
                            }
                    )

                    // Right tap zone (Next page) - invisible
                    Box(
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (state.currentPage < state.totalPages) {
                                    onNextPage()
                                }
                            }
                    )
                }

                // Reading controls overlay
                if (showControls) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.7f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                showControls = false
                            }
                    ) {
                        // Controls at the bottom
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(
                                    state.theme.surfaceColor,
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp
                                    )
                                )
                                .padding(16.dp)
                        ) {
                            // Page navigation buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Previous page button
                                Button(
                                    onClick = {
                                        onPreviousPage()
                                        showControls = false
                                    },
                                    enabled = state.currentPage > 1
                                ) {
                                    Text("Previous")
                                }

                                // Page info
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Page ${state.currentPage} of ${state.totalPages}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = state.theme.textColor
                                    )
                                    Text(
                                        text = "${((state.currentPage.toFloat() / state.totalPages) * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = state.theme.textColor.copy(alpha = 0.7f)
                                    )
                                }

                                // Next page button
                                Button(
                                    onClick = {
                                        onNextPage()
                                        showControls = false
                                    },
                                    enabled = state.currentPage < state.totalPages
                                ) {
                                    Text("Next")
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Progress bar
                            LinearProgressIndicator(
                                progress = if (state.totalPages > 0) state.currentPage.toFloat() / state.totalPages else 0f,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Tap instructions
                            Text(
                                text = "Tap left/right edges to turn pages • Volume up/down to turn pages • Tap center for controls",
                                style = MaterialTheme.typography.bodySmall,
                                color = state.theme.textColor.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Settings Panel (overlay)
        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showSettings = false
                    }
            ) {
                ReadingSettings(
                    fontSize = state.fontSize,
                    currentTheme = state.theme,
                    onFontSizeChange = onFontSizeChange,
                    onThemeChange = onThemeChange,
                    onDismiss = { showSettings = false },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun ReadingSettings(
    fontSize: Float,
    currentTheme: ReadingTheme,
    onFontSizeChange: (Float) -> Unit,
    onThemeChange: (ReadingTheme) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var volumeButtonsEnabled by remember { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth(0.9f)
            .wrapContentHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Reading Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Font size slider
            Text(
                text = "Font Size: ${fontSize.toInt()}sp",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Slider(
                value = fontSize,
                onValueChange = onFontSizeChange,
                valueRange = 12f..24f,
                steps = 11,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Reading theme selector
            Text(
                text = "Reading Theme",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ReadingTheme.values()) { theme ->
                    ThemeOption(
                        theme = theme,
                        isSelected = theme == currentTheme,
                        onThemeSelected = onThemeChange
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Volume buttons toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Volume buttons turn pages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Volume Up = Previous, Volume Down = Next",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = volumeButtonsEnabled,
                    onCheckedChange = { volumeButtonsEnabled = it }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    theme: ReadingTheme,
    isSelected: Boolean,
    onThemeSelected: (ReadingTheme) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onThemeSelected(theme) }
    ) {
        Card(
            modifier = Modifier.size(60.dp),
            colors = CardDefaults.cardColors(
                containerColor = theme.backgroundColor
            ),
            border = if (isSelected) {
                androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            } else {
                androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aa",
                    color = theme.textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = theme.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}