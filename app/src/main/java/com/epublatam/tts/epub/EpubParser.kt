package com.epublatam.tts.epub

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubChapter(
    val href: String,
    val title: String,
    val text: String,
)

data class EpubBook(
    val title: String,
    val chapters: List<EpubChapter>,
)

class EpubImporter(private val context: Context) {
    fun copyFromUri(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se pudo leer el archivo EPUB")
    }
}

object EpubParser {
    fun parse(file: File): EpubBook {
        ZipFile(file).use { zip ->
            val containerXml = zip.readEntry("META-INF/container.xml")
                ?: error("EPUB inválido: falta META-INF/container.xml")
            val rootPath = parseRootfilePath(containerXml)
            val opfBytes = zip.readEntry(rootPath)
                ?: error("EPUB inválido: no se encuentra $rootPath")
            val opfDir = rootPath.substringBeforeLast('/', missingDelimiterValue = "").let {
                if (it.isEmpty()) "" else "$it/"
            }
            val opfDoc = parseXml(opfBytes)
            val title = opfDoc.getElementsByTagName("dc:title").item(0)?.textContent?.trim()
                ?: opfDoc.getElementsByTagName("title").item(0)?.textContent?.trim()
                ?: file.nameWithoutExtension

            val manifest = mutableMapOf<String, String>()
            val manifestNodes = opfDoc.getElementsByTagName("item")
            for (i in 0 until manifestNodes.length) {
                val node = manifestNodes.item(i)
                val attrs = node.attributes ?: continue
                val id = attrs.getNamedItem("id")?.nodeValue ?: continue
                val href = attrs.getNamedItem("href")?.nodeValue ?: continue
                manifest[id] = href
            }

            val spineIds = mutableListOf<String>()
            val spineNodes = opfDoc.getElementsByTagName("itemref")
            for (i in 0 until spineNodes.length) {
                val idref = spineNodes.item(i).attributes?.getNamedItem("idref")?.nodeValue
                if (!idref.isNullOrBlank()) spineIds += idref
            }

            val chapters = mutableListOf<EpubChapter>()
            spineIds.forEachIndexed { index, id ->
                val href = manifest[id] ?: return@forEachIndexed
                val fullPath = opfDir + href
                val entryPath = normalizeZipPath(fullPath)
                val bytes = zip.readEntry(entryPath) ?: return@forEachIndexed
                val html = bytes.toString(Charsets.UTF_8)
                val text = extractText(html)
                if (text.isBlank()) return@forEachIndexed
                val chapterTitle = extractTitle(html).ifBlank { "Capítulo ${index + 1}" }
                chapters += EpubChapter(href = href, title = chapterTitle, text = text)
            }

            if (chapters.isEmpty()) {
                error("No se encontró texto legible. ¿EPUB con DRM o vacío?")
            }
            return EpubBook(title = title, chapters = chapters)
        }
    }

    private fun ZipFile.readEntry(path: String): ByteArray? {
        val normalized = normalizeZipPath(path)
        val entry = entries().asSequence().find {
            normalizeZipPath(it.name) == normalized || it.name == path
        } ?: return null
        return getInputStream(entry).use { it.readBytes() }
    }

    private fun normalizeZipPath(path: String): String =
        path.replace('\\', '/').trimStart('/').replace("./", "")

    private fun parseRootfilePath(containerXml: ByteArray): String {
        val doc = parseXml(containerXml)
        val nodes = doc.getElementsByTagName("rootfile")
        val fullPath = nodes.item(0)?.attributes?.getNamedItem("full-path")?.nodeValue
            ?: error("EPUB inválido: rootfile ausente")
        return normalizeZipPath(fullPath)
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(bytes.inputStream())
    }

    private fun extractText(html: String): String {
        val doc: Document = Jsoup.parse(html, "", Parser.xmlParser())
        doc.select("script, style, nav, [epub|type=pagebreak]").remove()
        val body = doc.body() ?: doc
        val text = body.text().replace('\u00A0', ' ').trim()
        return text.replace(Regex("\\s+\\n"), "\n").replace(Regex("[ \\t]{2,}"), " ")
    }

    private fun extractTitle(html: String): String {
        val doc = Jsoup.parse(html, "", Parser.xmlParser())
        return doc.selectFirst("h1, h2, title")?.text()?.trim().orEmpty()
    }
}
