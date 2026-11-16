package com.vault.legendas

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uma fala: início e fim em ms, texto original e (depois da tradução) em português. */
class Cue(val startMs: Long, var endMs: Long, val text: String, var pt: String? = null)

class TResult(
    val cues: List<Cue>, val lang: String, val translated: Boolean, val translateError: String?,
    val elapsedSec: Long, val audioSec: Long, val modelLabel: String,
)

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/** Núcleos "rápidos" do chip (os pequenos atrapalham o Whisper: todos esperam o mais lento). */
fun fastCores(): Int {
    val n = Runtime.getRuntime().availableProcessors()
    return try {
        val f = (0 until n).map { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
        val max = f.max()
        f.count { it >= max * 0.7 }.coerceIn(2, 6)
    } catch (_: Throwable) { (n / 2).coerceIn(2, 4) }
}

object Transcriber {
    private const val CHUNK_SEC = 60

    fun cancel() { runCatching { WhisperNative().nCancel(true) } }

    /** Extrai o áudio, transcreve com o Whisper e devolve as falas com horário. [onProgress] = (etapa, 0..1, detalhe). */
    suspend fun run(
        ctx: Context, uri: Uri, model: ModelInfo, langPref: String, threads: Int,
        onProgress: (String, Float, String) -> Unit,
    ): TResult = withContext(Dispatchers.Default) {
        val w = WhisperNative()
        w.nCancel(false)
        onProgress("Carregando o modelo ${model.label} ($threads threads)…", 0f, "")
        val ptr = w.nInit(ModelStore.file(ctx, model).path)
        if (ptr == 0L) throw IllegalStateException("Não foi possível carregar o modelo")
        try {
            var lang = langPref
            var durationMs = 0L
            var processedMs = 0L
            val cues = ArrayList<Cue>()
            val t0 = SystemClock.elapsedRealtime()
            val job = currentCoroutineContext()

            AudioDecoder.decode(
                ctx, uri, CHUNK_SEC,
                isActive = { job.isActive },
                onInfo = { durationMs = it },
            ) { chunk, offMs ->
                val chunkMs = chunk.size * 1000L / AudioDecoder.RATE
                onProgress("Transcrevendo…", progressOf(offMs, durationMs), "Processando o trecho de ${fmtClock(offMs)} (1 min de áudio por vez)…${preview(cues)}")
                val rc = w.nRun(ptr, threads, lang, chunk)
                if (!job.isActive) return@decode
                if (rc != 0) throw IllegalStateException("O Whisper falhou (código $rc)")
                // fixa o idioma detectado no 1º trecho, para os seguintes não oscilarem
                if (lang == "auto") lang = w.nLang(ptr)
                for (i in 0 until w.nCount(ptr)) {
                    val text = String(w.nText(ptr, i), Charsets.UTF_8).trim()
                    if (text.isEmpty() || w.nNoSpeech(ptr, i) > 0.6f) continue
                    if (Regex("""^[\[(*♪].*[\])*♪]$""").matches(text)) continue   // [Música], (aplausos)…
                    val s = w.nT0(ptr, i) * 10 + offMs
                    val e = w.nT1(ptr, i) * 10 + offMs
                    cues.add(Cue(s, maxOf(e, s + 600), text))
                }
                processedMs = offMs + chunkMs
                val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
                val speed = processedMs.toFloat() / elapsed
                onProgress(
                    "Transcrevendo…", progressOf(processedMs, durationMs),
                    "${fmtClock(processedMs)} de ${fmtClock(durationMs)} · ${"%.1f".format(Locale.US, speed)}x o tempo real${preview(cues)}",
                )
            }
            job.ensureActive()
            // arruma sobreposições
            cues.sortBy { it.startMs }
            for (i in 0 until cues.size - 1) if (cues[i].endMs > cues[i + 1].startMs) cues[i].endMs = maxOf(cues[i].startMs + 400, cues[i + 1].startMs)
            TResult(
                cues, lang, false, null,
                (SystemClock.elapsedRealtime() - t0) / 1000, maxOf(durationMs, processedMs) / 1000, model.label,
            )
        } finally {
            w.nFree(ptr)
        }
    }

    private fun preview(cues: List<Cue>) = cues.lastOrNull()?.let { "\nÚltima fala: «${it.text.take(80)}»" } ?: ""

    private fun progressOf(pos: Long, total: Long) = if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f
}

object Translate {
    /** Traduz as falas para português com o ML Kit (offline depois de baixar o pacote do idioma). */
    suspend fun toPortuguese(src: String, cues: List<Cue>, onProgress: (Int, Int) -> Unit): Boolean {
        val code = TranslateLanguage.fromLanguageTag(src) ?: throw IllegalStateException("Idioma \"$src\" sem tradutor offline")
        if (code == TranslateLanguage.PORTUGUESE) { cues.forEach { it.pt = it.text }; return true }
        val tr = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(code).setTargetLanguage(TranslateLanguage.PORTUGUESE).build()
        )
        try {
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            cues.forEachIndexed { i, c ->
                currentCoroutineContext().ensureActive()
                c.pt = tr.translate(c.text).await()
                if (i % 5 == 0) onProgress(i + 1, cues.size)
            }
            onProgress(cues.size, cues.size)
            return true
        } finally {
            tr.close()
        }
    }
}

fun fmtClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun srtTime(ms: Long): String {
    val h = ms / 3_600_000; val m = (ms % 3_600_000) / 60_000; val s = (ms % 60_000) / 1000; val r = ms % 1000
    return "%02d:%02d:%02d,%03d".format(h, m, s, r)
}

/** Monta um arquivo .srt. [pick] escolhe o texto de cada fala (original ou português). */
fun toSrt(cues: List<Cue>, pick: (Cue) -> String): String = buildString {
    cues.forEachIndexed { i, c ->
        append(i + 1).append('\n')
        append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n')
        append(pick(c)).append("\n\n")
    }
}
