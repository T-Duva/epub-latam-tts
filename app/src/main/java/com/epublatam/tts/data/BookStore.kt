package com.epublatam.tts.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private val Context.dataStore by preferencesDataStore("library")

data class BookMeta(
    val id: String,
    val title: String,
    val fileName: String,
    val lastChapterIndex: Int = 0,
)

class BookStore(private val context: Context) {
    private val booksKey = stringPreferencesKey("books_json")
    private val elevenKey = stringPreferencesKey("elevenlabs_api_key")
    private val booksDir: File
        get() = File(context.filesDir, "books").also { it.mkdirs() }

    val books: Flow<List<BookMeta>> = context.dataStore.data.map { prefs ->
        parseBooks(prefs[booksKey].orEmpty())
    }

    suspend fun getElevenLabsKey(): String =
        context.dataStore.data.first()[elevenKey].orEmpty()

    suspend fun setElevenLabsKey(key: String) {
        context.dataStore.edit { it[elevenKey] = key.trim() }
    }

    suspend fun listBooks(): List<BookMeta> = books.first()

    fun bookFile(meta: BookMeta): File = File(booksDir, meta.fileName)

    suspend fun importBook(source: File, titleHint: String?): BookMeta {
        val id = UUID.randomUUID().toString()
        val destName = "$id.epub"
        val dest = File(booksDir, destName)
        source.copyTo(dest, overwrite = true)
        val meta = BookMeta(
            id = id,
            title = titleHint?.takeIf { it.isNotBlank() } ?: source.nameWithoutExtension,
            fileName = destName,
        )
        val current = listBooks().toMutableList()
        current.add(0, meta)
        save(current)
        return meta
    }

    suspend fun updateTitle(id: String, title: String) {
        val current = listBooks().map {
            if (it.id == id) it.copy(title = title) else it
        }
        save(current)
    }

    suspend fun updateLastChapter(id: String, chapterIndex: Int) {
        val current = listBooks().map {
            if (it.id == id) it.copy(lastChapterIndex = chapterIndex.coerceAtLeast(0)) else it
        }
        save(current)
    }

    suspend fun deleteBook(id: String) {
        val current = listBooks()
        val target = current.find { it.id == id }
        if (target != null) {
            bookFile(target).delete()
            save(current.filterNot { it.id == id })
        }
    }

    private suspend fun save(books: List<BookMeta>) {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("fileName", b.fileName)
                    .put("lastChapterIndex", b.lastChapterIndex),
            )
        }
        context.dataStore.edit { it[booksKey] = arr.toString() }
    }

    private fun parseBooks(raw: String): List<BookMeta> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        BookMeta(
                            id = o.getString("id"),
                            title = o.getString("title"),
                            fileName = o.getString("fileName"),
                            lastChapterIndex = o.optInt("lastChapterIndex", 0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
