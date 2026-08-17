package com.epublatam.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.epublatam.tts.data.BookMeta
import com.epublatam.tts.ui.LibraryScreen
import com.epublatam.tts.ui.ReaderScreen
import com.epublatam.tts.update.InstallNeed

class MainActivity : ComponentActivity() {
    private val libraryVm: LibraryViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LibraryViewModel(application) as T
            }
        }
    }

    private val pickEpub = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) libraryVm.importEpub(uri)
    }

    private val requestInstallPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        libraryVm.onReturnedFromPermissionSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val green = Color(0xFF1B4D3E)
            val scheme = lightColorScheme(
                primary = green,
                secondary = Color(0xFF3D6B5A),
                tertiary = Color(0xFFC4A35A),
            )
            MaterialTheme(colorScheme = scheme) {
                Surface {
                    val nav = rememberNavController()
                    val books by libraryVm.books.collectAsState()
                    val busy by libraryVm.busy.collectAsState()
                    val message by libraryVm.message.collectAsState()
                    val updateInfo by libraryVm.updateAvailable.collectAsState()
                    val updateStatus by libraryVm.updateStatus.collectAsState()
                    val installNeed by libraryVm.needsInstallPermission.collectAsState()
                    var selected by remember { mutableStateOf<BookMeta?>(null) }

                    NavHost(navController = nav, startDestination = "library") {
                        composable("library") {
                            LibraryScreen(
                                books = books,
                                busy = busy,
                                message = message,
                                updateInfo = updateInfo,
                                updateStatus = updateStatus,
                                needsPermission = installNeed is InstallNeed.Permission,
                                onAdd = {
                                    pickEpub.launch(
                                        arrayOf("application/epub+zip", "application/octet-stream", "*/*"),
                                    )
                                },
                                onOpen = { book ->
                                    selected = book
                                    nav.navigate("reader")
                                },
                                onDelete = { libraryVm.deleteBook(it.id) },
                                onDismissMessage = { libraryVm.clearMessage() },
                                onInstallUpdate = { libraryVm.installUpdate(it) },
                                onGrantInstallPermission = {
                                    requestInstallPermission.launch(libraryVm.openPermissionSettings())
                                },
                                onDownloadInBrowser = { libraryVm.downloadInBrowser(it) },
                            )
                        }
                        composable("reader") {
                            val book = selected
                            if (book == null) {
                                nav.popBackStack()
                            } else {
                                val readerVm: ReaderViewModel = viewModel(
                                    key = book.id,
                                    factory = object : ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                            return ReaderViewModel(application) as T
                                        }
                                    },
                                )
                                val state by readerVm.state.collectAsState()
                                androidx.compose.runtime.LaunchedEffect(book.id) {
                                    readerVm.openBook(book)
                                }
                                ReaderScreen(
                                    state = state,
                                    onBack = {
                                        readerVm.stop()
                                        nav.popBackStack()
                                    },
                                    onPlayPause = { readerVm.playPause() },
                                    onStop = { readerVm.stop() },
                                    onPrev = { readerVm.prevChapter() },
                                    onNext = { readerVm.nextChapter() },
                                    onRate = { readerVm.setRate(it) },
                                    onSelectChapter = { readerVm.selectChapter(it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        libraryVm.onReturnedFromPermissionSettings()
    }
}
