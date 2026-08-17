package com.epublatam.tts.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Descarga e instala el modelo Piper es_AR-daniela-high (sherpa-onnx) una sola vez.
 * ~110 MB; queda en almacenamiento interno de la app.
 */
class PiperModelInstaller(context: Context) {
    companion object {
        private const val TAG = "PiperModel"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_AR-daniela-high.tar.bz2"
        private const val ROOT_NAME = "vits-piper-es_AR-daniela-high"
        private const val ONNX_NAME = "es_AR-daniela-high.onnx"
        private const val TOKENS_NAME = "tokens.txt"
    }

    private val rootDir = File(context.filesDir, "piper/$ROOT_NAME")
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    val modelPath: String get() = File(rootDir, ONNX_NAME).absolutePath
    val tokensPath: String get() = File(rootDir, TOKENS_NAME).absolutePath
    val dataDirPath: String get() = File(rootDir, "espeak-ng-data").absolutePath

    fun isReady(): Boolean {
        val onnx = File(rootDir, ONNX_NAME)
        val tokens = File(rootDir, TOKENS_NAME)
        val data = File(rootDir, "espeak-ng-data")
        return onnx.exists() && onnx.length() > 50_000_000L &&
            tokens.exists() && data.isDirectory && (data.list()?.isNotEmpty() == true)
    }

    suspend fun ensureReady(onProgress: (String) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (isReady()) {
            onProgress("Voz argentina lista (offline)")
            return@withContext
        }
        onProgress("Descargando voz argentina (~110 MB, solo una vez)…")
        val tmpBz2 = File(rootDir.parentFile, "$ROOT_NAME.tar.bz2.tmp")
        val extractTmp = File(rootDir.parentFile, "$ROOT_NAME.extract")
        rootDir.parentFile?.mkdirs()
        if (extractTmp.exists()) extractTmp.deleteRecursively()
        extractTmp.mkdirs()

        try {
            val req = Request.Builder()
                .url(MODEL_URL)
                .header("User-Agent", "epub-latam-tts")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("No se pudo descargar el modelo (${resp.code})")
                }
                val total = resp.body?.contentLength() ?: -1L
                val body = resp.body ?: error("Respuesta vacía del modelo")
                var read = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    FileOutputStream(tmpBz2).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt()
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct
                                    onProgress("Descargando voz argentina… $pct%")
                                }
                            }
                        }
                    }
                }
            }

            onProgress("Instalando voz argentina…")
            extractTarBz2(tmpBz2, extractTmp)

            // El tar suele traer una carpeta raíz con el mismo nombre
            val unpacked = File(extractTmp, ROOT_NAME).takeIf { it.isDirectory } ?: extractTmp
            if (rootDir.exists()) rootDir.deleteRecursively()
            if (!unpacked.renameTo(rootDir)) {
                unpacked.copyRecursively(rootDir, overwrite = true)
                unpacked.deleteRecursively()
            }
            if (!isReady()) {
                error("El modelo se descargó incompleto. Probá de nuevo con Wi‑Fi.")
            }
            onProgress("Voz argentina lista (offline)")
        } catch (e: Exception) {
            Log.e(TAG, "ensureReady failed", e)
            rootDir.deleteRecursively()
            throw e
        } finally {
            tmpBz2.delete()
            extractTmp.deleteRecursively()
        }
    }

    private fun extractTarBz2(bz2: File, dest: File) {
        BufferedInputStream(bz2.inputStream()).use { fis ->
            BZip2CompressorInputStream(fis).use { bzis ->
                TarArchiveInputStream(bzis).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val outFile = File(dest, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                            continue
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                    }
                }
            }
        }
    }
}
