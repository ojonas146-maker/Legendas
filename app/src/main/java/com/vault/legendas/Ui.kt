package com.vault.legendas

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LANGS = listOf(
    "auto" to "Detectar", "es" to "Español", "ru" to "Русский", "en" to "English", "pt" to "Português",
    "fr" to "Français", "de" to "Deutsch", "it" to "Italiano", "ja" to "日本語", "zh" to "中文",
    "ar" to "العربية", "ko" to "한국어", "tr" to "Türkçe", "pl" to "Polski", "uk" to "Українська",
)

private sealed interface Run {
    data object Idle : Run
    class Working(val stage: String, val progress: Float, val detail: String) : Run
    class Done(val result: TResult) : Run
    class Failed(val msg: String) : Run
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoName by remember { mutableStateOf("") }
    var modelId by rememberSaveable { mutableStateOf("small") }
    var lang by rememberSaveable { mutableStateOf("auto") }
    var run by remember { mutableStateOf<Run>(Run.Idle) }
    var dlId by remember { mutableStateOf<String?>(null) }
    var dlProgress by remember { mutableFloatStateOf(0f) }
    var refresh by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var saveVariant by remember { mutableIntStateOf(0) }   // 0 original · 1 português

    val working = run is Run.Working
    val model = MODELS.first { it.id == modelId }
    val modelReady = remember(modelId, refresh) { ModelStore.has(ctx, model) }

    // tela ligada enquanto processa
    val view = LocalView.current
    DisposableEffect(working) { view.keepScreenOn = working; onDispose { view.keepScreenOn = false } }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            videoUri = uri
            videoName = try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null } ?: "vídeo"
            run = Run.Idle
        }
    }
    val saveSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val r = (run as? Run.Done)?.result
        if (uri != null && r != null) {
            try {
                val text = toSrt(r.cues) { c -> if (saveVariant == 1) (c.pt ?: c.text) else c.text }
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(ctx, "Legenda salva", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(ctx, "Não foi possível salvar", Toast.LENGTH_SHORT).show() }
        }
    }

    fun download(m: ModelInfo) {
        if (dlId != null) return
        dlId = m.id; dlProgress = 0f
        scope.launch {
            try {
                ModelStore.download(ctx, m) { dlProgress = it }
                Toast.makeText(ctx, "Modelo ${m.label} baixado", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.makeText(ctx, "Falha no download: ${e.message ?: "verifique a internet"}", Toast.LENGTH_LONG).show()
            } finally { dlId = null; refresh++ }
        }
    }

    fun generate() {
        val uri = videoUri ?: return
        job = scope.launch {
            run = Run.Working("Preparando…", 0f, "")
            try {
                val base = Transcriber.run(ctx.applicationContext, uri, model, lang) { stage, p, d -> run = Run.Working(stage, p, d) }
                var translated = false
                var err: String? = null
                if (base.cues.isNotEmpty()) {
                    try {
                        translated = Translate.toPortuguese(base.lang, base.cues) { done, total ->
                            run = Run.Working("Traduzindo para português…", done.toFloat() / total, "$done de $total falas")
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Throwable) { err = e.message ?: "falha na tradução" }
                }
                run = Run.Done(TResult(base.cues, base.lang, translated, err, base.elapsedSec, base.audioSec, base.modelLabel))
            } catch (e: CancellationException) {
                run = Run.Idle
            } catch (e: Throwable) {
                run = Run.Failed(e.message ?: "erro desconhecido")
            }
        }
    }

    val result = (run as? Run.Done)?.result
    if (playing && videoUri != null && result != null) {
        PlayerScreen(videoUri!!, result) { playing = false }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                Text("Legendas", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Protótipo · tudo roda no celular, sem enviar nada para a internet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }

        // ---- 1. modelo
        item {
            Panel("1 · Modelo de transcrição") {
                MODELS.forEach { m ->
                    val has = remember(m.id, refresh) { ModelStore.has(ctx, m) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !working) { modelId = m.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(modelId == m.id, { modelId = m.id }, enabled = !working)
                        Column(Modifier.weight(1f)) {
                            Text("${m.label} · ${m.mb} MB", fontWeight = FontWeight.SemiBold)
                            Text(m.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            dlId == m.id -> Text("${(dlProgress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            has -> IconButton({ ModelStore.delete(ctx, m); refresh++ }, enabled = !working) { Icon(Icons.Rounded.Delete, "Apagar modelo") }
                            else -> FilledTonalButton({ download(m) }, enabled = dlId == null && !working) { Text("Baixar") }
                        }
                    }
                    if (dlId == m.id) LinearProgressIndicator(progress = { dlProgress }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                }
            }
        }

        // ---- 2. idioma
        item {
            Panel("2 · Idioma da fala") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LANGS) { (code, name) ->
                        FilterChip(lang == code, { lang = code }, { Text(name) }, enabled = !working)
                    }
                }
                Text(
                    "Se você sabe o idioma, escolher aqui costuma acertar mais do que \"Detectar\".",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 3. vídeo
        item {
            Panel("3 · Vídeo") {
                OutlinedButton({ pickVideo.launch(arrayOf("video/*")) }, Modifier.fillMaxWidth().height(52.dp), enabled = !working) {
                    Icon(Icons.Rounded.VideoFile, null); Spacer(Modifier.width(8.dp))
                    Text(if (videoUri == null) "Escolher vídeo" else "Trocar vídeo")
                }
                if (videoUri != null) Text(videoName, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    { generate() }, Modifier.fillMaxWidth().height(54.dp),
                    enabled = videoUri != null && modelReady && !working && dlId == null,
                ) {
                    Icon(Icons.Rounded.ClosedCaption, null); Spacer(Modifier.width(8.dp))
                    Text(if (!modelReady) "Baixe o modelo primeiro" else "Gerar legenda")
                }
            }
        }

        // ---- progresso / resultado
        when (val r = run) {
            is Run.Working -> item {
                var secs by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) { while (true) { delay(1000); secs++ } }
                Panel(r.stage) {
                    LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Tempo decorrido: ${fmtClock(secs * 1000)}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (r.detail.isNotEmpty()) Text(r.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Mantenha o app aberto. A tela fica ligada enquanto processa.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ Transcriber.cancel(); job?.cancel() }) { Text("Cancelar") }
                }
            }
            is Run.Failed -> item {
                Panel("Algo deu errado") {
                    Text(r.msg, color = MaterialTheme.colorScheme.error)
                    Text("Se o app fechou sozinho ao gerar, me avise: pode ser o modo de processamento do aparelho.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is Run.Done -> item {
                val x = r.result
                Panel("Pronto · ${x.cues.size} falas") {
                    val lname = LANGS.firstOrNull { it.first == x.lang }?.second ?: x.lang
                    Text("Idioma: $lname · Modelo ${x.modelLabel}", fontSize = 13.sp)
                    val speed = if (x.elapsedSec > 0) "%.1f".format(java.util.Locale.US, x.audioSec.toFloat() / x.elapsedSec) else "-"
                    Text("Vídeo de ${fmtClock(x.audioSec * 1000)} processado em ${fmtClock(x.elapsedSec * 1000)} (${speed}x o tempo real)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (x.translateError != null) Text("Tradução não concluída: ${x.translateError}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    if (x.cues.isEmpty()) Text("Nenhuma fala encontrada. Tente outro modelo ou escolha o idioma manualmente.", color = MaterialTheme.colorScheme.error)
                    else {
                        Button({ playing = true }, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Assistir com legenda") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ saveVariant = 0; saveSrt.launch("legenda-original.srt") }, Modifier.weight(1f)) { Text(".srt original", fontSize = 13.sp) }
                            OutlinedButton({ saveVariant = 1; saveSrt.launch("legenda-pt.srt") }, Modifier.weight(1f), enabled = x.translated) { Text(".srt português", fontSize = 13.sp) }
                        }
                    }
                }
            }
            Run.Idle -> {}
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

// ---------- Player com legenda sincronizada

@Composable
private fun PlayerScreen(uri: Uri, result: TResult, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cues = result.cues
    BackHandler { onBack() }
    val exo = remember {
        ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true }
    }
    DisposableEffect(Unit) { onDispose { exo.release() } }

    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(exo) { while (true) { pos = exo.currentPosition; delay(80) } }
    var mode by remember { mutableIntStateOf(if (result.translated) 2 else 0) }   // 0 original · 1 português · 2 ambos
    var offset by remember { mutableLongStateOf(0L) }                              // ajuste de sincronia em ms

    val now = pos + offset
    val current = remember(now, cues) { cues.firstOrNull { now >= it.startMs && now <= it.endMs } }
    val currentIdx = if (current == null) -1 else cues.indexOf(current)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
            Text("Vídeo com legenda", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Box(Modifier.fillMaxWidth().height(260.dp).background(Color.Black)) {
            AndroidView(factory = { c -> PlayerView(c).apply { this.player = exo; useController = true } }, modifier = Modifier.fillMaxSize())
            if (current != null) Column(
                Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val pt = current.pt
                if ((mode == 1 || mode == 2) && pt != null) SubText(pt, 17.sp, Color.White)
                if (mode == 0 || mode == 2 || pt == null) SubText(current.text, if (mode == 2 && pt != null) 13.sp else 17.sp, if (mode == 2 && pt != null) Color(0xFFFFE082) else Color.White)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(mode == 0, { mode = 0 }, { Text("Original") })
            FilterChip(mode == 1, { mode = 1 }, { Text("Português") }, enabled = result.translated)
            FilterChip(mode == 2, { mode = 2 }, { Text("Ambos") }, enabled = result.translated)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sincronia", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            OutlinedButton({ offset -= 500 }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−0,5 s") }
            Text("%+.1f s".format(java.util.Locale.US, offset / 1000f), Modifier.padding(horizontal = 10.dp), fontWeight = FontWeight.Bold)
            OutlinedButton({ offset += 500 }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+0,5 s") }
        }
        Text("Toque numa fala para pular até ela", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val listState = rememberLazyListState()
        LaunchedEffect(currentIdx) { if (currentIdx >= 0) listState.animateScrollToItem((currentIdx - 2).coerceAtLeast(0)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            itemsIndexed(cues) { i, c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (i == currentIdx) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { exo.seekTo((c.startMs - offset).coerceAtLeast(0)) }.padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(fmtClock(c.startMs), Modifier.width(52.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(c.text, fontSize = 14.sp)
                        if (c.pt != null && c.pt != c.text) Text(c.pt!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubText(text: String, size: androidx.compose.ui.unit.TextUnit, color: Color) {
    Text(
        text, color = color, fontSize = size, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 2.dp).background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
