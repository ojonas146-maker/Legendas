package com.vault.legendas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Ponte para o whisper.cpp (libwhisper compilada junto com o app). */
class WhisperNative {
    companion object { init { System.loadLibrary("legendas") } }

    external fun nInit(path: String): Long
    external fun nFree(ptr: Long)
    external fun nCancel(flag: Boolean)
    external fun nRun(ptr: Long, threads: Int, lang: String, audio: FloatArray): Int
    external fun nCount(ptr: Long): Int
    external fun nText(ptr: Long, i: Int): ByteArray
    external fun nT0(ptr: Long, i: Int): Long
    external fun nT1(ptr: Long, i: Int): Long
    external fun nNoSpeech(ptr: Long, i: Int): Float
    external fun nLang(ptr: Long): String
}

class ModelInfo(val id: String, val label: String, val file: String, val mb: Int, val note: String)

val MODELS = listOf(
    ModelInfo("tiny", "Tiny", "ggml-tiny-q5_1.bin", 31, "Muito rápido, erra bastante"),
    ModelInfo("base", "Base", "ggml-base-q5_1.bin", 57, "Rápido, bom para testar"),
    ModelInfo("small", "Small", "ggml-small-q5_1.bin", 181, "Recomendado: equilíbrio"),
    ModelInfo("medium", "Medium", "ggml-medium-q5_0.bin", 514, "Melhor qualidade, bem mais lento"),
)

private const val MODEL_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

object ModelStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: ModelInfo) = File(dir(ctx), m.file)

    /** Considera baixado se o arquivo tem pelo menos ~85% do tamanho esperado. */
    fun has(ctx: Context, m: ModelInfo): Boolean {
        val f = file(ctx, m)
        return f.exists() && f.length() > m.mb * 1_000_000L * 85 / 100
    }

    fun delete(ctx: Context, m: ModelInfo) { file(ctx, m).delete() }

    suspend fun download(ctx: Context, m: ModelInfo, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file(ctx, m)
        val part = File(target.path + ".part")
        val conn = URL(MODEL_BASE_URL + m.file).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Legendas/0.1")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("Servidor respondeu ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (m.mb * 1_000_000L)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) { lastReport = done; onProgress((done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("Não foi possível salvar o modelo")
            onProgress(1f)
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
