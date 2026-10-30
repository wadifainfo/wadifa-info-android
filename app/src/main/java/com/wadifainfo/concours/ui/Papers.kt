@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wadifainfo.concours.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.wadifainfo.concours.*
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PapersVm(app: Application) : AndroidViewModel(app) {
    private val repo = ConcoursApp.repo(app)
    var q by mutableStateOf("")
    var state by mutableStateOf<Load<List<Paper>>>(Load.Loading)
    var total by mutableIntStateOf(0); var loadingMore by mutableStateOf(false)
    private var page = 1; private var lang = ""; private var job: Job? = null

    fun start(lang: String) { if (this.lang != lang) { this.lang = lang; reload() } }

    fun reload(debounce: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(400)
            state = Load.Loading; page = 1
            state = try { repo.papers(lang, q, 1).let { total = it.data.total; Load.Ok(it.data.items, it.offline) } } catch (e: Exception) { Load.Err(e.message) }
        }
    }

    fun more() {
        val cur = state as? Load.Ok ?: return
        if (loadingMore || cur.offline || cur.data.size >= total) return
        loadingMore = true
        viewModelScope.launch {
            runCatching { repo.papers(lang, q, page + 1) }.onSuccess { page++; state = Load.Ok(Paging.merge(cur.data, it.data.items) { p -> p.id }, false) }
            loadingMore = false
        }
    }
}

@Composable
fun PapersScreen(push: (Screen) -> Unit) {
    val vm: PapersVm = viewModel(); val lang = lang()
    LaunchedEffect(lang) { vm.start(lang) }
    Column {
        OutlinedTextField(vm.q, { vm.q = it; vm.reload(debounce = true) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true)
        when (val s = vm.state) {
            Load.Loading -> Spinner()
            is Load.Err -> ErrorBox { vm.reload() }
            is Load.Ok -> {
                if (s.offline) Box(Modifier.padding(horizontal = 16.dp)) { OfflineBanner { vm.reload() } }
                Text(text(R.string.results, if (s.offline) s.data.size else vm.total), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.data.isEmpty()) Text(stringResource(R.string.empty), Modifier.padding(16.dp))
                LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.data, key = { it.id }) { p -> PaperCard(p, lang) { push(Screen.PaperDetail(p.id)) } }
                    if (!s.offline && s.data.size < vm.total) item { LaunchedEffect(s.data.size) { vm.more() }; if (vm.loadingMore) Spinner() }
                }
            }
        }
    }
}

@Composable
fun PaperCard(p: Paper, lang: String, onClick: () -> Unit) = WfCard(onClick = onClick) {
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(Icons.Outlined.Description)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.grade ?: p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.employer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                p.year?.let { Tag(it.toString(), icon = Icons.Outlined.CalendarMonth) }
                if (p.pages > 0) Tag(text(R.string.pages, p.pages))
                if (p.hasCorrection) Tag(stringResource(R.string.correction), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Icons.Outlined.TaskAlt)
            }
        }
    }
}

@Composable
fun PaperDetailScreen(id: Int) {
    val ctx = LocalContext.current; val lang = lang(); val repo = ConcoursApp.repo(ctx); val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<Paper>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, lang, attempt) {
        state = Load.Loading
        state = try { repo.paper(id, lang).let { Load.Ok(it.data, it.offline) } } catch (e: Exception) { Load.Err(e.message) }
    }
    val fav by repo.favorites.isFav("paper", id.toString()).collectAsState(0)
    when (val s = state) {
        Load.Loading -> Spinner()
        is Load.Err -> ErrorBox { attempt++ }
        is Load.Ok -> {
            val p = s.data
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.offline) item { OfflineBanner() }
                item {
                    Row {
                        Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        IconButton({ scope.launch { if (fav > 0) repo.favorites.remove("paper", id.toString())
                            else repo.favorites.add(Favorite("paper", id.toString(), p.title, System.currentTimeMillis())) } }) {
                            Icon(if (fav > 0) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(R.string.fav_add))
                        }
                    }
                    Tag(stringResource(R.string.past_exam), MaterialTheme.colorScheme.primaryContainer)
                }
                p.description?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ open(ctx, p.pdf) }) { Text(stringResource(R.string.open_pdf)) }
                        OutlinedButton({ open(ctx, p.url) }) { Text(stringResource(R.string.open_site)) }
                    }
                }
                p.source?.let { item { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                items(p.images) { url ->
                    AsyncImage(url, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
                p.documents.forEach { d -> item { TextButton({ open(ctx, d.url) }) { Text(d.label ?: d.url) } } }
                p.correctionHtml?.let { html ->
                    item {
                        Text(stringResource(R.string.correction), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        // Corrections on the site are written by the editorial team: say so, never present them as an official key.
                        Tag(stringResource(R.string.correction_editorial), MaterialTheme.colorScheme.tertiaryContainer)
                    }
                    item { Text(AnnotatedString.fromHtml(html), style = MaterialTheme.typography.bodyMedium) }
                }
                item { TextButton({ open(ctx, p.url) }) { Text(stringResource(R.string.source)) } }
            }
        }
    }
}
