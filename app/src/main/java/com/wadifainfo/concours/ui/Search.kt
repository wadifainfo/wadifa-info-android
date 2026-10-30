@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.wadifainfo.concours.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wadifainfo.concours.*
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchVm(app: Application) : AndroidViewModel(app) {
    private val repo = ConcoursApp.repo(app)
    var text by mutableStateOf("")
    var query by mutableStateOf(Query())
    var filters by mutableStateOf<Filters?>(null)
    var suggestions by mutableStateOf<List<Parse.Suggestion>>(emptyList())
    var state by mutableStateOf<Load<List<Offer>>>(Load.Loading)
    var total by mutableIntStateOf(0)
    var specMatches by mutableIntStateOf(-1)
    var loadingMore by mutableStateOf(false)
    /** Lives in the ViewModel so the results keep their scroll position when the user opens an offer and comes back. */
    val listState = androidx.compose.foundation.lazy.LazyListState()
    /** Set when the criteria change; the screen scrolls to the top once and clears it (re-entering does not). */
    var scrollTop by mutableStateOf(false)
    private var page = 1; private var lang = ""; private var job: Job? = null; private var sJob: Job? = null

    fun start(lang: String, initial: Query?) {
        if (initial != null && initial != query) { query = initial; text = initial.q ?: "" ; this.lang = lang; reload(); return }
        if (this.lang == lang) return
        this.lang = lang
        viewModelScope.launch { filters = runCatching { repo.filters() }.getOrNull() }
        reload()
    }

    fun onText(t: String) {
        text = t
        sJob?.cancel()
        sJob = viewModelScope.launch { delay(250); suggestions = repo.suggest(lang, t) }
    }

    fun submit() {
        suggestions = emptyList(); repo.prefs.addRecentSearch(text)
        set(query.copy(q = text.trim().ifBlank { null }))
    }

    fun pick(s: Parse.Suggestion) { suggestions = emptyList(); text = if (s.cat == "employeur") s.value else ""; set(Parse.apply(s, query)) }

    fun set(q: Query) { if (q != query) scrollTop = true; query = q; reload() }

    fun reset() { text = ""; suggestions = emptyList(); set(Query(sort = query.sort)) }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            state = Load.Loading; page = 1
            state = try { repo.offers(lang, query, 1).let { total = it.data.total; specMatches = it.data.specialtyMatches; Load.Ok(it.data.items, it.offline, it.syncedAt) } }
                    catch (e: Exception) { Load.Err(e.message) }
        }
    }

    fun more() {
        val cur = state as? Load.Ok ?: return
        if (loadingMore || cur.offline || cur.data.size >= total) return
        loadingMore = true
        viewModelScope.launch {
            runCatching { repo.offers(lang, query, page + 1) }.onSuccess { page++; state = cur.copy(data = Paging.merge(cur.data, it.data.items) { o -> o.id }) }
            loadingMore = false
        }
    }

    fun save(label: String, notify: Boolean) = viewModelScope.launch { repo.saved.add(SavedSearch.of(label, query, notify)) }
}

@Composable
fun SearchScreen(initial: Query?, push: (Screen) -> Unit) {
    val vm: SearchVm = viewModel(); val lang = lang(); val ctx = LocalContext.current; val focus = LocalFocusManager.current
    LaunchedEffect(lang, initial) { vm.start(lang, initial) }
    var showFilters by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    val listState = vm.listState
    LaunchedEffect(vm.scrollTop) { if (vm.scrollTop) { listState.scrollToItem(0); vm.scrollTop = false } }
    val repo = ConcoursApp.repo(ctx); val follow = rememberFollow()
    val followed by repo.favorites.all().collectAsState(emptyList())
    val followedIds = remember(followed) { followed.filter { it.type == "offer" }.map { it.refId.toLong() }.toSet() }
    val q = vm.query; val f = vm.filters
    fun label(v: String?, list: List<FilterValue>?) = v?.let { x -> list?.firstOrNull { it.value == x }?.let { if (lang == "fr") it.fr else it.ar } ?: x }

    Column {
        OutlinedTextField(vm.text, vm::onText, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), shape = MaterialTheme.shapes.extraLarge,
            placeholder = { Text(stringResource(R.string.search_hint)) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (vm.text.isNotEmpty()) IconButton({ vm.onText(""); vm.submit() }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) } },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submit(); focus.clearFocus() }))

        // Typeahead: the site's diploma / specialty / city / employer suggestions. Picking one sets the matching filter.
        if (vm.suggestions.isNotEmpty()) Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                vm.suggestions.take(8).forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { vm.pick(s); focus.clearFocus() }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(stringResource(when (s.cat) { "diplome" -> R.string.diploma; "specialite" -> R.string.specialty; "ville" -> R.string.city; else -> R.string.employer }))
                        Text(s.label, Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        } else if (vm.text.isEmpty() && q.isEmpty) {
            val recent = remember(vm.state) { ConcoursApp.repo(ctx).prefs.recentSearches }
            if (recent.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.take(5).forEach { r -> SuggestionChip({ vm.text = r; vm.submit() }, { Text(r) }) }
            }
        }

        // Active filters as removable chips + entry points to filters / sort / save.
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The chip shows how many filters are active, so hidden filters never explain an empty list silently.
            val active = listOf(q.diplome, q.specialite, q.region, q.city, q.contract, q.etype).count { it != null } +
                (if (q.electronic) 1 else 0) + (if (q.includeClosed) 1 else 0)
            FilterChip(active > 0, { showFilters = true }, { Text(if (active > 0) "${stringResource(R.string.filters)} · $active" else stringResource(R.string.filters)) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Tune, null, Modifier.size(18.dp)) })
            SortMenu(q.sort) { vm.set(q.copy(sort = it)) }
            q.diplome?.let { InputChip(true, { vm.set(q.copy(diplome = null)) }, { Text(label(it, f?.diplomas)!!) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }) }
            q.specialite?.let { InputChip(true, { vm.set(q.copy(specialite = null)) }, { Text(it) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }) }
            q.region?.let { InputChip(true, { vm.set(q.copy(region = null)) }, { Text(label(it, f?.regions)!!) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }) }
            q.city?.let { InputChip(true, { vm.set(q.copy(city = null)) }, { Text(it) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }) }
            q.contract?.let { InputChip(true, { vm.set(q.copy(contract = null)) }, { Text(stringResource(if (it == 1) R.string.regular else R.string.contract)) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }) }
            q.etype?.let { InputChip(true, { vm.set(q.copy(etype = null)) }, { Text(stringResource(etypeLabel(it))) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }) }
            if (q.electronic) InputChip(true, { vm.set(q.copy(electronic = false)) }, { Text(stringResource(R.string.online_only)) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) })
            if (q.includeClosed) InputChip(true, { vm.set(q.copy(includeClosed = false)) }, { Text(stringResource(R.string.include_closed)) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) })
            if (!q.isEmpty) {
                AssistChip({ vm.reset() }, { Text(stringResource(R.string.reset)) })
                AssistChip({ showSave = true }, { Text(stringResource(R.string.save_search)) })
            }
        }

        when (val s = vm.state) {
            Load.Loading -> SkeletonList(modifier = Modifier.padding(horizontal = 16.dp))
            is Load.Err -> ErrorBox { vm.reload() }
            is Load.Ok -> {
                if (s.offline) Box(Modifier.padding(horizontal = 16.dp)) { OfflineBanner(s.syncedAt) { vm.reload() } }
                Text(pluralStringResourceSafe(R.plurals.results_n, if (s.offline) s.data.size else vm.total), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.data.isEmpty()) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.empty))
                    if (!q.isEmpty) OutlinedButton({ vm.reset() }) { Text(stringResource(R.string.reset)) }
                    if (!q.includeClosed) TextButton({ vm.set(q.copy(includeClosed = true)) }) { Text(stringResource(R.string.include_closed)) }
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.data.forEachIndexed { i, o ->
                        // Specialty search: offers that name no specialty come after the real matches, under a divider.
                        if (i == vm.specMatches && !s.offline) item(key = "spec-divider") {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                SectionTitle(stringResource(R.string.no_specialty_title))
                                Text(stringResource(R.string.no_specialty_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item(key = o.id) {
                            OfferCard(o, lang, followed = o.id in followedIds, onFollow = { follow(o, o.id !in followedIds) }) { push(Screen.OfferDetail(o.id)) }
                        }
                        // In-feed ad after the 6th result, then every 15: never above the first results, never next to
                        // the follow button, labelled and spaced so it cannot be mistaken for an offer.
                        if (i == 5 || (i > 5 && (i - 5) % 15 == 0)) item(key = "ad-$i") { AdBanner(Modifier.padding(vertical = 4.dp), slot = "search-$i") }
                    }
                    if (!s.offline && s.data.size < vm.total) item { LaunchedEffect(s.data.size) { vm.more() }; if (vm.loadingMore) Spinner() }
                }
            }
        }
    }

    if (showFilters) ModalBottomSheet({ showFilters = false }) {
        FilterSheet(q, f, lang) { vm.set(it); showFilters = false }
    }
    if (showSave) SaveSearchDialog(defaultLabel = listOfNotNull(q.q, q.specialite, label(q.diplome, f?.diplomas), q.city, label(q.region, f?.regions))
        .joinToString(" · ").ifBlank { stringResource(R.string.my_search) }, onDismiss = { showSave = false }) { name, notify ->
        vm.save(name, notify); showSave = false
        Toast.makeText(ctx, ctx.getString(R.string.saved_ok), Toast.LENGTH_SHORT).show()
        if (notify) enableAlerts(ctx)
    }
}

@Composable
fun pluralStringResourceSafe(id: Int, n: Int) = pluralText(id, n, n)

fun etypeLabel(t: String) = when (t) { "state" -> R.string.et_state; "local" -> R.string.et_local; else -> R.string.et_public_body }

@Composable
private fun SortMenu(sort: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val opts = listOf(4 to R.string.sort_newest, 5 to R.string.sort_deadline, 3 to R.string.sort_posts, 2 to R.string.sort_salary)
    Box {
        FilterChip(false, { open = true }, { Text(stringResource(opts.firstOrNull { it.first == sort }?.second ?: R.string.sort_newest)) })
        DropdownMenu(open, { open = false }) {
            opts.forEach { (v, l) -> DropdownMenuItem({ Text(stringResource(l)) }, { onPick(v); open = false }) }
        }
    }
}

@Composable
private fun FilterSheet(start: Query, f: Filters?, lang: String, onApply: (Query) -> Unit) {
    var q by remember { mutableStateOf(start) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.filters))
        if (f != null) {
            FilterMenu(stringResource(R.string.diploma), f.diplomas, q.diplome, lang, Modifier.fillMaxWidth()) { q = q.copy(diplome = it?.value) }
            FilterMenu(stringResource(R.string.region), f.regions, q.region, lang, Modifier.fillMaxWidth()) { q = q.copy(region = it?.value) }
        } else Text(stringResource(R.string.filters_offline), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(q.specialite ?: "", { q = q.copy(specialite = it.ifBlank { null }) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.specialty)) })
        OutlinedTextField(q.city ?: "", { q = q.copy(city = it.ifBlank { null }) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.city)) })
        Text(stringResource(R.string.recruitment_type), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(q.contract == null, { q = q.copy(contract = null) }, { Text(stringResource(R.string.any)) })
            FilterChip(q.contract == 1, { q = q.copy(contract = 1) }, { Text(stringResource(R.string.regular)) })
            FilterChip(q.contract == 2, { q = q.copy(contract = 2) }, { Text(stringResource(R.string.contract)) })
        }
        Text(stringResource(R.string.employer_type), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(q.etype == null, { q = q.copy(etype = null) }, { Text(stringResource(R.string.any)) })
            listOf("state", "local", "public_body").forEach { t -> FilterChip(q.etype == t, { q = q.copy(etype = t) }, { Text(stringResource(etypeLabel(t))) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.online_only), Modifier.weight(1f)); Switch(q.electronic, { q = q.copy(electronic = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.include_closed), Modifier.weight(1f)); Switch(q.includeClosed, { q = q.copy(includeClosed = it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ q = Query(sort = q.sort) }) { Text(stringResource(R.string.reset)) }
            Button({ onApply(q) }, Modifier.weight(1f)) { Text(stringResource(R.string.apply_filters)) }
        }
    }
}

@Composable
fun SaveSearchDialog(defaultLabel: String, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(defaultLabel) }
    var notify by remember { mutableStateOf(true) }
    AlertDialog(onDismiss, title = { Text(stringResource(R.string.save_search)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.name)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notify_new), Modifier.weight(1f)); Switch(notify, { notify = it })
                }
            }
        },
        confirmButton = { TextButton({ onSave(name.ifBlank { defaultLabel }, notify) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
