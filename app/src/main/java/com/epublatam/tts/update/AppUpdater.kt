package com.epublatam.tts.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.epublatam.tts.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val tag: String,
    val apkUrl: String,
    val notes: String,
)

class AppUpdater(private val app: Application) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    suspend fun check() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/T-Duva/epub-latam-tts/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "epub-latam-tts")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext
                val json = JSONObject(resp.body?.string().orEmpty())
                val tag = json.optString("tag_name").removePrefix("v")
                val notes = json.optString("body").orEmpty()
                val assets = json.optJSONArray("assets") ?: return@withContext
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isBlank()) return@withContext
                if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                    _available.value = UpdateInfo(tag, "v$tag", apkUrl, notes)
                    _status.value = "Hay actualización $tag"
                } else {
                    _available.value = null
                }
            }
        } catch (_: Exception) {
            // silencioso al chequear
        }
    }

    suspend fun downloadAndInstall(info: UpdateInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            _status.value = "Descargando ${info.versionName}…"
            val req = Request.Builder().url(info.apkUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _status.value = "No se pudo descargar (${resp.code})"
                    return@withContext false
                }
                val dir = File(app.cacheDir, "updates").also { it.mkdirs() }
                val apk = File(dir, "epub-latam-tts-${info.versionName}.apk")
                resp.body?.byteStream()?.use { input ->
                    apk.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext false
                _status.value = "Instalá la actualización…"
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !app.packageManager.canRequestPackageInstalls()
                    ) {
                        app.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${app.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        _status.value = "Activá “instalar apps desconocidas” y tocá Actualizar de nuevo."
                        return@withContext
                    }
                    installApk(app, apk)
                }
                true
            }
        } catch (e: Exception) {
            _status.value = "Error al actualizar: ${e.message}"
            false
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-', ' ')
            .mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val r = parts(remote)
        val l = parts(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    companion object {
        fun installApk(context: Context, apk: File) {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}
