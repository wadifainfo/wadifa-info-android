package com.wadifainfo.concours.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Official documents (notices, arrêtés, lists) downloaded into app-private storage: no storage permission on any Android
 * version, readable offline afterwards, removed with the app. The content is checked, never the name: only bytes that
 * start with %PDF are kept. Word forms (1 of 25 official files in the 2026-10-14 audit) and HTML error pages are
 * rejected with a specific reason so the screen can offer the browser instead.
 */
class Docs(ctx: Context, private val dao: DownloadDao) {
    private val dir = File(ctx.filesDir, "docs").apply { mkdirs() }
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()

    sealed interface State {
        data class Progress(val read: Long, val total: Long) : State
        data class Done(val file: File) : State
        data class Failed(val reason: Reason) : State
    }
    enum class Reason { OFFLINE, NOT_FOUND, NOT_PDF, OTHER_FILE, TOO_LARGE, SERVER }

    companion object {
        const val MAX_BYTES = 40L * 1024 * 1024
        /** Offline copies are a cache: oldest files go first above this total. */
        const val CACHE_BYTES = 150L * 1024 * 1024
        fun isPdf(head: ByteArray) = head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()
        /** .docx/.xlsx/.odt (ZIP "PK\u0003\u0004") or legacy .doc/.xls (OLE D0 CF 11 E0). */
        fun isOfficeFile(head: ByteArray) = head.size >= 4 && (
            (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()) ||
            (head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()))
        fun classify(head: ByteArray): Reason? = when { isPdf(head) -> null; isOfficeFile(head) -> Reason.OTHER_FILE; else -> Reason.NOT_PDF }
        fun name(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".pdf"
    }

    suspend fun cached(url: String): File? = dao.byUrl(url)?.let { File(dir, it.file) }?.takeIf { it.exists() && it.length() > 0 }

    fun download(url: String, label: String): Flow<State> = flow {
        cached(url)?.let { emit(State.Done(it)); return@flow }
        val target = File(dir, name(url)); val tmp = File(dir, target.name + ".part")
        var stored = false
        try {
            val req = Request.Builder().url(url).header("User-Agent", "WadifaInfo-Android/2.2").build()
            val result: State = try {
                http.newCall(req).execute().use { r ->
                    when {
                        r.code == 404 || r.code == 410 -> State.Failed(Reason.NOT_FOUND)
                        !r.isSuccessful -> State.Failed(Reason.SERVER)
                        else -> {
                            val body = r.body ?: return@use State.Failed(Reason.SERVER)
                            val total = body.contentLength()
                            if (total > MAX_BYTES) return@use State.Failed(Reason.TOO_LARGE)
                            var read = 0L; var checked = false; var bad: Reason? = null
                            body.byteStream().use { input -> tmp.outputStream().use { out ->
                                val buf = ByteArray(32 * 1024); var lastEmit = 0L
                                while (true) {
                                    val n = input.read(buf); if (n < 0) break
                                    if (!checked) { checked = true; bad = classify(buf.copyOf(n)); if (bad != null) break }
                                    out.write(buf, 0, n); read += n
                                    if (read > MAX_BYTES) { bad = Reason.TOO_LARGE; break }
                                    if (read - lastEmit > 64 * 1024) { lastEmit = read; emit(State.Progress(read, total)) }
                                }
                            } }
                            if (bad != null || read == 0L) State.Failed(bad ?: Reason.NOT_PDF)
                            else {
                                // Reported as downloaded only once the validated file is in place and recorded.
                                target.delete()
                                if (!tmp.renameTo(target)) return@use State.Failed(Reason.SERVER)
                                dao.add(Download(url, target.name, label, read, System.currentTimeMillis()))
                                stored = true
                                trim()
                                State.Done(target)
                            }
                        }
                    }
                }
            } catch (e: java.io.IOException) { State.Failed(Reason.OFFLINE) }
            emit(result)
        } finally {
            tmp.delete()                         // also on cancellation (user left the screen mid-download)
            if (!stored && !target.exists()) dao.remove(url)
        }
    }.flowOn(Dispatchers.IO)

    /** Keeps the offline copies under [CACHE_BYTES], deleting the oldest downloads first. */
    private suspend fun trim() {
        var total = 0L
        for (d in dao.newestFirst()) {
            val f = File(dir, d.file)
            total += f.length()
            if (total > CACHE_BYTES) { f.delete(); dao.remove(d.url) }
        }
    }

    suspend fun delete(url: String) { cached(url)?.delete(); dao.remove(url) }
}
