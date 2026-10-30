package com.wadifainfo.concours.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.wadifainfo.concours.ConcoursApp
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.Docs
import com.wadifainfo.concours.open
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Official PDFs open inside the app: downloaded once (progress + retry), then read offline from app storage and
 * rendered with the platform PdfRenderer — no PDF-reader app and no storage permission needed (both were recurring
 * failure causes in the official app's reviews). Pinch to zoom; any failure says why and offers the browser.
 */
@Composable
fun PdfScreen(url: String, label: String) {
    val ctx = LocalContext.current; val repo = ConcoursApp.repo(ctx)
    var state by remember { mutableStateOf<Docs.State>(Docs.State.Progress(0, -1)) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(url, attempt) { state = Docs.State.Progress(0, -1); repo.docs.download(url, label).collect { state = it } }

    when (val s = state) {
        is Docs.State.Progress -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.downloading), style = MaterialTheme.typography.titleMedium)
            if (s.total > 0) LinearProgressIndicator({ s.read.toFloat() / s.total }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (s.read > 0) Text("%.1f MB".format(s.read / 1048576f) + if (s.total > 0) " / %.1f MB".format(s.total / 1048576f) else "",
                style = MaterialTheme.typography.labelMedium)
        }
        is Docs.State.Failed -> {
            // Retry only helps for transient failures; a Word file, a dead link or a huge file needs the browser instead.
            val transient = s.reason == Docs.Reason.OFFLINE || s.reason == Docs.Reason.SERVER
            val icon = when (s.reason) {
                Docs.Reason.OFFLINE -> Icons.Outlined.CloudOff
                Docs.Reason.NOT_FOUND -> Icons.Outlined.LinkOff
                Docs.Reason.OTHER_FILE -> Icons.Outlined.Description
                else -> Icons.Outlined.ErrorOutline
            }
            EmptyState(icon, label, stringResource(when (s.reason) {
                Docs.Reason.OFFLINE -> R.string.pdf_offline
                Docs.Reason.NOT_FOUND -> R.string.pdf_not_found
                Docs.Reason.NOT_PDF -> R.string.pdf_not_pdf
                Docs.Reason.OTHER_FILE -> R.string.pdf_other_file
                Docs.Reason.TOO_LARGE -> R.string.pdf_too_large
                Docs.Reason.SERVER -> R.string.pdf_server
            })) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (transient) { Button({ attempt++ }) { Text(stringResource(R.string.retry)) }; OutlinedButton({ open(ctx, url) }) { Text(stringResource(R.string.open_in_browser)) } }
                    else Button({ open(ctx, url) }) { Text(stringResource(R.string.open_in_browser)) }
                }
            }
        }
        is Docs.State.Done -> PdfPages(s.file, onShare = { shareFile(ctx, s.file, label) })
    }
}

@Composable
private fun PdfPages(file: File, onShare: () -> Unit) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) { Text(stringResource(R.string.pdf_not_pdf), Modifier.padding(32.dp)); return }
    val lock = remember { Mutex() }   // PdfRenderer allows one open page at a time
    var scale by remember { mutableFloatStateOf(1f) }
    val hs = rememberScrollState()
    val pages = rememberLazyListState()
    val page by remember { derivedStateOf { pages.firstVisibleItemIndex + 1 } }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text(R.string.page_of, page, renderer.pageCount), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            val zoomOut = stringResource(R.string.zoom_out); val zoomIn = stringResource(R.string.zoom_in)
            TextButton({ scale = (scale - 0.5f).coerceAtLeast(1f) }, Modifier.semantics { contentDescription = zoomOut }, enabled = scale > 1f) { Text("−") }
            Text("${(scale * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
            TextButton({ scale = (scale + 0.5f).coerceAtMost(3f) }, Modifier.semantics { contentDescription = zoomIn }, enabled = scale < 3f) { Text("+") }
            TextButton(onShare) { Text(stringResource(R.string.share)) }
        }
        // Zoom = wider pages inside a horizontal scroller: every part of every page stays reachable, one finger
        // scrolls normally, two fingers pinch.
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF5F6368))) {
            val widthPx = constraints.maxWidth
            val w = maxWidth
            Box(Modifier.fillMaxSize().horizontalScroll(hs)) {
            LazyColumn(state = pages, modifier = Modifier.width(w * scale).fillMaxHeight()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) {
                                scale = (scale * ev.calculateZoom()).coerceIn(1f, 3f)
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                },
                contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(List(renderer.pageCount) { it }) { _, i ->
                    // Each visible page is one bitmap; it is freed as soon as the page leaves the list (long notices on
                    // 2 GB phones). Width capped at 1440 px: ~12 MB per page instead of ~22 MB at 2000 px.
                    var bmp by remember(i, widthPx) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i, widthPx) {
                        bmp = withContext(Dispatchers.IO) {
                            lock.withLock {
                                runCatching {
                                    renderer.openPage(i).use { p ->
                                        val w = (widthPx * 1.4f).toInt().coerceAtMost(1440)
                                        val h = (w * p.height.toFloat() / p.width).toInt()
                                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                            it.eraseColor(AColor.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        }
                                    }
                                }.getOrNull()
                            }
                        }
                    }
                    DisposableEffect(i, widthPx) { onDispose { bmp?.recycle(); bmp = null } }
                    bmp?.takeIf { !it.isRecycled }?.let { Image(it.asImageBitmap(), text(R.string.page_n, i + 1), Modifier.fillMaxWidth()) }
                        ?: Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White))
                }
            }
            }
        }
    }
}

private fun shareFile(ctx: android.content.Context, f: File, label: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, label).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}
