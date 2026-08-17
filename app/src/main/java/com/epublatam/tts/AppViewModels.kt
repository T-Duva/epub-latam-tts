package com.epublatam.tts

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.epublatam.tts.data.BookMeta
import com.epublatam.tts.data.BookStore
import com.epublatam.tts.epub.EpubBook
import com.epublatam.tts.epub.EpubImporter
import com.epublatam.tts.epub.EpubParser
import com.epublatam.tts.tts.PersonaVoice
import com.epublatam.tts.tts.TtsStatus
import com.epublatam.tts.update.AppUpdater
import com.epublatam.tts.update.InstallNeed
import com.epublatam.tts.update.UpdateInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ReaderUiState(
    val bookMeta: BookMeta? = null,
    val epub: EpubBook? = null,
    val chapterIndex: Int = 0,
    val isPlaying: Boolean = false,
    val rate: Float = 0.9f,
    val status: TtsStatus? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val progress: String? = null,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = BookStore(app)
    private val importer = EpubImporter(app)
    private val updater = AppUpdater(app)
    val books = store.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val updateAvailable = updater.available
    val updateStatus = updater.status
    val needsInstallPermission = updater.needsInstallPermission

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch { updater.check() }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun installUpdate(info: UpdateInfo) {
        viewModelScope.launch { updater.downloadAndInstall(info) }
    }

    fun openPermissionSettings() = updater.permissionSettingsIntent()

    fun onReturnedFromPermissionSettings() {
        if (updater.tryInstallPending()) return
        val need = needsInstallPermission.value
        if (need is InstallNeed.Permission && !updater.canInstallPackages()) {
            _message.value = "Todavía no está el permiso. Activá “Permitir de esta fuente”."
        }
    }

    fun downloadInBrowser(info: UpdateInfo) {
        updater.openDownloadInBrowser(info)
    }

    fun importEpub(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val tmp = File(getApplication<Application>().cacheDir, "import_${System.currentTimeMillis()}.epub")
                importer.copyFromUri(uri, tmp)
                val parsed = runCatching { EpubParser.parse(tmp) }.getOrNull()
                val meta = store.importBook(tmp, parsed?.title)
                if (parsed != null && parsed.title.isNotBlank()) {
                    store.updateTitle(meta.id, parsed.title)
                }
                tmp.delete()
                _message.value = "Libro importado: ${parsed?.title ?: meta.title}"
            } catch (e: Exception) {
                _message.value = e.message ?: "Error al importar"
            } finally {
                _busy.value = false
            }
        }
    }

    fun deleteBook(id: String) {
        viewModelScope.launch { store.deleteBook(id) }
    }

    fun bookStore(): BookStore = store
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val store = BookStore(app)
    private val voice = PersonaVoice(app)
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private var speakJob: Job? = null

    fun openBook(meta: BookMeta) {
        viewModelScope.launch {
            _state.value = ReaderUiState(loading = true, bookMeta = meta)
            try {
                val file = store.bookFile(meta)
                val epub = EpubParser.parse(file)
                val idx = meta.lastChapterIndex.coerceIn(0, epub.chapters.lastIndex)
                val status = try {
                    voice.prepare { msg ->
                        _state.value = _state.value.copy(progress = msg, loading = true)
                    }
                } catch (e: Exception) {
                    _state.value = ReaderUiState(
                        bookMeta = meta,
                        epub = epub,
                        chapterIndex = idx,
                        loading = false,
                        error = e.message ?: "No se pudo preparar la voz.",
                    )
                    return@launch
                }
                _state.value = ReaderUiState(
                    bookMeta = meta,
                    epub = epub,
                    chapterIndex = idx,
                    status = status,
                    loading = false,
                    progress = null,
                )
            } catch (e: Exception) {
                _state.value = ReaderUiState(
                    bookMeta = meta,
                    loading = false,
                    error = e.message ?: "No se pudo abrir el EPUB",
                )
            }
        }
    }

    fun setRate(rate: Float) {
        _state.value = _state.value.copy(rate = rate)
    }

    fun selectChapter(index: Int) {
        stop()
        val epub = _state.value.epub ?: return
        val safe = index.coerceIn(0, epub.chapters.lastIndex)
        _state.value = _state.value.copy(chapterIndex = safe)
        viewModelScope.launch {
            _state.value.bookMeta?.let { store.updateLastChapter(it.id, safe) }
        }
    }

    fun playPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun play() {
        val s = _state.value
        val epub = s.epub ?: return
        // Saltar capítulos sin texto legible
        var idx = s.chapterIndex
        while (idx <= epub.chapters.lastIndex && epub.chapters[idx].text.trim().length < 20) {
            idx++
        }
        if (idx > epub.chapters.lastIndex) {
            _state.value = s.copy(
                isPlaying = false,
                error = "Este libro no tiene texto legible (¿solo imágenes o DRM?).",
            )
            return
        }
        if (idx != s.chapterIndex) {
            _state.value = s.copy(chapterIndex = idx)
        }
        val chapter = epub.chapters[idx]
        speakJob?.cancel()
        // No llamar voice.stop() acá: race con el job anterior. speak() inicia sesión nueva.
        _state.value = _state.value.copy(isPlaying = true, error = null)
        speakJob = viewModelScope.launch {
            try {
                voice.speak(
                    chapter.text,
                    _state.value.rate,
                    onProgress = { msg ->
                        _state.value = _state.value.copy(
                            status = voice.lastStatus.copy(message = msg),
                        )
                    },
                ) {
                    val cur = _state.value
                    val next = cur.chapterIndex + 1
                    if (cur.epub != null && next <= cur.epub.chapters.lastIndex) {
                        _state.value = cur.copy(chapterIndex = next, isPlaying = false)
                        viewModelScope.launch {
                            cur.bookMeta?.let { store.updateLastChapter(it.id, next) }
                            play()
                        }
                    } else {
                        _state.value = cur.copy(isPlaying = false)
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isPlaying = false,
                    error = e.message ?: "Error de voz",
                    status = voice.lastStatus,
                )
            }
        }
    }

    fun pause() {
        voice.pause()
        voice.stop()
        speakJob?.cancel()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun stop() {
        speakJob?.cancel()
        voice.stop()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun nextChapter() {
        val s = _state.value
        val epub = s.epub ?: return
        if (s.chapterIndex < epub.chapters.lastIndex) selectChapter(s.chapterIndex + 1)
    }

    fun prevChapter() {
        if (_state.value.chapterIndex > 0) selectChapter(_state.value.chapterIndex - 1)
    }

    override fun onCleared() {
        stop()
        voice.release()
        super.onCleared()
    }
}
