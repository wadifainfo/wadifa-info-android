package com.wadifainfo.concours.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wadifainfo.concours.*
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.*
import android.app.Application
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Home data lives here, not in the composable: coming back from an offer or another tab shows the same content at the
 * same scroll position instead of a skeleton and a new download. It refreshes when the language or the profile changes,
 * when it is older than [STALE_MS], or on pull-to-refresh; a failed refresh keeps what was shown (marked offline).
 */
class HomeVm(app: Application) : AndroidViewModel(app) {
    private val repo = ConcoursApp.repo(app)
    data class Blocks(val mine: Repo.Loaded<Page<Offer>>?, val closing: Repo.Loaded<Page<Offer>>?, val newest: Repo.Loaded<Page<Offer>>?)
    var blocks by mutableStateOf<Load<Blocks>>(Load.Loading); private set
    var refreshing by mutableStateOf(false); private set
    var filters by mutableStateOf<Filters?>(null); private set
    private var key: Pair<String, Query>? = null
    private var loadedAt = 0L
    private var job: Job? = null

    fun load(lang: String, mine: Query, force: Boolean = false) {
        val k = lang to mine
        val shown = blocks as? Load.Ok
        val fresh = shown != null && !shown.offline && System.currentTimeMillis() - loadedAt < STALE_MS
        if (!force && k == key && (fresh || job?.isActive == true)) return
        val keep = shown?.takeIf { k == key }
        key = k; job?.cancel()
        if (filters == null) viewModelScope.launch { filters = runCatching { repo.filters() }.getOrNull() }
        job = viewModelScope.launch {
            if (keep == null) blocks = Load.Loading else refreshing = true
            val r = coroutineScope {
                val a = async { if (mine.isEmpty) null else runCatching { repo.offers(lang, mine, 1) }.getOrNull() }
                val b = async { runCatching { repo.offers(lang, Query(sort = 5), 1) }.getOrNull() }
                val c = async { runCatching { repo.offers(lang, Query(sort = 4), 1) }.getOrNull() }
                Blocks(a.await(), b.await(), c.await())
            }
            blocks = if (r.closing == null && r.newest == null) keep?.copy(offline = true) ?: Load.Err(null)
            else Load.Ok(r, r.newest?.offline ?: true, r.newest?.syncedAt ?: 0).also {
                loadedAt = System.currentTimeMillis()
                r.newest?.takeIf { n -> !n.offline }?.let { n -> repo.prefs.setLastOpenCount(lang, n.data.total) }
            }
            refreshing = false
        }
    }

    companion object { const val STALE_MS = 10 * 60_000L }
}

/**
 * Dashboard: a green header with the live count and the search entry, diploma shortcuts, then "for you"
 * (profile), closing soon, newest, recently opened, and tools. Each block links to the full, filtered search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(push: (Screen) -> Unit, openSearch: (Query) -> Unit) {
    val ctx = LocalContext.current; val lang = lang(); val repo = ConcoursApp.repo(ctx); val p = repo.prefs
    val mine = Query(diplome = p.diploma, specialite = p.specialty, region = p.region)
    val closing = Query(sort = 5)
    val newest = Query(sort = 4)
    val vm: HomeVm = viewModel()
    LaunchedEffect(lang, mine) { vm.load(lang, mine) }
    val blocks = vm.blocks; val filters = vm.filters
    val followed by repo.favorites.all().collectAsState(emptyList())
    val followedIds = remember(followed) { followed.filter { it.type == "offer" }.map { it.refId.toLong() }.toSet() }
    val follow = rememberFollow()
    val onFollow: (Offer) -> Unit = { o -> follow(o, o.id !in followedIds) }
    val recent by repo.recent.all().collectAsState(emptyList())
    // Live count when online; offline, the last count seen online, labelled as such (never a made-up number).
    val ok = blocks as? Load.Ok
    val liveCount = ok?.data?.newest?.takeIf { !it.offline }?.data?.total
    val lastCount = remember(lang, ok) { if (liveCount == null) p.lastOpenCount(lang) else null }

    PullToRefreshBox(vm.refreshing, { vm.load(lang, mine, force = true) }) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Hero(liveCount, lastCount) { openSearch(Query()) } }
        filters?.diplomas?.takeIf { it.isNotEmpty() }?.let { dips ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.by_diploma), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dips.take(8), key = { it.value }) { d ->
                            AssistChip(onClick = { openSearch(Query(diplome = d.value)) }, label = { Text(if (lang == "fr") d.fr else d.ar, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Outlined.School, null, Modifier.size(18.dp)) }, shape = MaterialTheme.shapes.large)
                        }
                    }
                }
            }
        }
        if (mine.isEmpty) item {
            WfCard(Modifier.padding(horizontal = 16.dp), onClick = { push(Screen.Profile) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Outlined.AutoAwesome)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.set_profile_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.set_profile_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        when (val b = blocks) {
            Load.Loading -> item { SkeletonList(3, Modifier.padding(horizontal = 16.dp)) }
            is Load.Err -> item { ErrorBox { vm.load(lang, mine, force = true) } }
            is Load.Ok -> {
                if (b.offline) item { Box(Modifier.padding(horizontal = 16.dp)) { OfflineBanner(b.syncedAt) { vm.load(lang, mine, force = true) } } }
                // One offer appears once on Home: each section skips offers already shown above it.
                val shown = mutableSetOf<Long>()
                fun pick(p: Page<Offer>) = p.items.filter { shown.add(it.id) }.take(4)
                b.data.mine?.let { section(R.string.for_you, Icons.Outlined.AutoAwesome, pick(it.data), it.data.total, lang, push, followedIds, onFollow) { openSearch(mine) } }
                b.data.closing?.let { section(R.string.closing_soon, Icons.Outlined.Alarm, pick(it.data), it.data.total, lang, push, followedIds, onFollow) { openSearch(closing) } }
                b.data.newest?.let { section(R.string.latest, Icons.Outlined.NewReleases, pick(it.data), it.data.total, lang, push, followedIds, onFollow) { openSearch(newest) } }
                item { AdBanner(Modifier.padding(horizontal = 16.dp)) }
                if (!b.offline && b.syncedAt > 0) item {
                    Text(text(R.string.updated_ago, ago(b.syncedAt)), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.recently_viewed), Icons.Outlined.History, Modifier.padding(horizontal = 16.dp)) }
            item {
                WfCard(Modifier.padding(horizontal = 16.dp)) {
                    recent.take(4).forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ListItem(headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = r.employer?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            leadingContent = { Avatar(r.employer, 34.dp) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            modifier = Modifier.clickable { push(Screen.OfferDetail(r.offerId)) })
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.tools), Icons.Outlined.Handyman, Modifier.padding(horizontal = 16.dp)) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile(Icons.Outlined.MenuBook, stringResource(R.string.tab_prep), Modifier.weight(1f)) { push(Screen.Prep) }
                ToolTile(Icons.Outlined.Payments, stringResource(R.string.salaries), Modifier.weight(1f)) {
                    open(ctx, "https://www.wadifa-info.com/fr/grille-salariale-fonction-publique-maroc")
                }
            }
        }
    }
    }
}

/** Full-bleed brand header: what the app is for, how many offers are open right now, and the search entry. */
@Composable
private fun Hero(openCount: Int?, lastCount: Int?, onSearch: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val ink = heroInk()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)).background(heroColor())
        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.home_hero_title), style = MaterialTheme.typography.headlineSmall, color = ink)
        Text(when {
                openCount != null -> pluralText(R.plurals.open_now, openCount, openCount)
                lastCount != null -> pluralText(R.plurals.open_last_known, lastCount, lastCount)
                else -> stringResource(R.string.home_hero_sub)
            },
            style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = .88f))
        Surface(onClick = onSearch, shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Search, null, tint = cs.primary)
                Text(stringResource(R.string.search_hint), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun IconTile(icon: ImageVector, size: androidx.compose.ui.unit.Dp = 40.dp) =
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .55f), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }

@Composable
private fun ToolTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) =
    WfCard(modifier, onClick = onClick) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(icon)
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: Int, icon: ImageVector, shown: List<Offer>, total: Int, lang: String,
    push: (Screen) -> Unit, followedIds: Set<Long>, onFollow: (Offer) -> Unit, more: () -> Unit) {
    if (shown.isEmpty()) return
    item(key = "h$title") {
        SectionHeader(stringResource(title), icon, Modifier.padding(horizontal = 16.dp)) {
            TextButton(more) { Text(text(R.string.see_all_n, total)) }
        }
    }
    items(shown, key = { "$title-${it.id}" }) { o ->
        Box(Modifier.padding(horizontal = 16.dp)) { OfferCard(o, lang, followed = o.id in followedIds, onFollow = { onFollow(o) }) { push(Screen.OfferDetail(o.id)) } }
    }
}
