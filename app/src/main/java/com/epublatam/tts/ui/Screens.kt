package com.epublatam.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epublatam.tts.ReaderUiState
import com.epublatam.tts.data.BookMeta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<BookMeta>,
    busy: Boolean,
    message: String?,
    onAdd: () -> Unit,
    onOpen: (BookMeta) -> Unit,
    onDelete: (BookMeta) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("EPUB Latam TTS") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Añadir EPUB")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                "Importá un EPUB y escuchalo en español latino. " +
                    "Con API key de Google Cloud usa voz Neural; sin key, TTS del sistema (es-MX).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            message?.let {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onDismissMessage() },
                ) {
                    Text(it, Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
            if (books.isEmpty() && !busy) {
                Text("Todavía no hay libros. Tocá + para añadir un EPUB.")
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(book) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(book.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Último capítulo: ${book.lastChapterIndex + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { onDelete(book) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Borrar")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRate: (Float) -> Unit,
    onSelectChapter: (Int) -> Unit,
) {
    val epub = state.epub
    val chapter = epub?.chapters?.getOrNull(state.chapterIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.bookMeta?.title ?: "Lectura",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            state.status?.let {
                Text(
                    "Voz: ${it.voiceLabel}${it.message?.let { m -> " — $m" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            if (epub != null) {
                Text(
                    "Capítulo ${state.chapterIndex + 1} / ${epub.chapters.size}: ${chapter?.title.orEmpty()}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onPrev, enabled = state.chapterIndex > 0) {
                        Icon(Icons.Default.SkipPrevious, null)
                    }
                    Button(onClick = onPlayPause) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                        Text(if (state.isPlaying) " Pausar" else " Play")
                    }
                    Button(onClick = onStop) {
                        Icon(Icons.Default.Stop, null)
                    }
                    Button(
                        onClick = onNext,
                        enabled = state.chapterIndex < epub.chapters.lastIndex,
                    ) {
                        Icon(Icons.Default.SkipNext, null)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Velocidad: ${"%.1f".format(state.rate)}x")
                Slider(
                    value = state.rate,
                    onValueChange = onRate,
                    valueRange = 0.6f..1.8f,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    chapter?.text.orEmpty(),
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text("Capítulos", style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.height(140.dp)) {
                    items(epub.chapters.size) { i ->
                        val c = epub.chapters[i]
                        Text(
                            "${i + 1}. ${c.title}",
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChapter(i) }
                                .padding(vertical = 6.dp),
                            color = if (i == state.chapterIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
