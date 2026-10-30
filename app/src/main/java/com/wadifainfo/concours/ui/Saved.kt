package com.wadifainfo.concours.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wadifainfo.concours.*
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.*
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** "Suivis": followed offers sorted by deadline (the deadline tracker), saved searches with their alert switch, papers. */
@Composable
fun SavedScreen(push: (Screen) -> Unit, openSearch: (Query) -> Unit) {
    val ctx = LocalContext.current; val lang = lang(); val repo = ConcoursApp.repo(ctx); val scope = rememberCoroutineScope()
    val favs by repo.favorites.all().collectAsState(emptyList())
    val searches by repo.saved.all().collectAsState(emptyList())
    val offers = favs.filter { it.type == "offer" }.sortedWith(compareBy<Favorite> {
        when (Logic.status(it.deadline)) { Logic.Status.CLOSED -> 2; Logic.Status.UNKNOWN -> 1; else -> 0 } }.thenBy { Logic.parseDate(it.deadline) })
    val papers = favs.filter { it.type == "paper" }
    // Removal is immediate; the snackbar offers to put the item back exactly as it was (same id, same alert baseline).
    val sb = LocalSnackbar.current; val removedMsg = stringResource(R.string.removed); val undoLabel = stringResource(R.string.undo)
    fun undoable(remove: suspend () -> Unit, restore: suspend () -> Unit) = scope.launch {
        remove()
        if (sb.showSnackbar(removedMsg, undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) restore()
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionHeader(stringResource(R.string.followed_offers), Icons.Outlined.EventAvailable) }
        if (offers.isEmpty()) item { EmptyState(Icons.Outlined.BookmarkAdd, stringResource(R.string.followed_offers), stringResource(R.string.no_followed)) }
        items(offers, key = { "f" + it.refId }) { f ->
            WfCard(onClick = { push(Screen.OfferDetail(f.refId.toLong())) }) {
                Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(f.employer)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(f.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        f.employer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(f.deadline); fmtDate(f.deadline, lang)?.let { Meta(Icons.Outlined.Event, it) }
                        }
                    }
                    IconButton({ undoable({ repo.removeFavorite(f) }, { repo.restoreFavorite(f) }) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.outline) }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.saved_searches), Icons.Outlined.SavedSearch, Modifier.padding(top = 10.dp)) }
        if (searches.isEmpty()) item { EmptyState(Icons.Outlined.NotificationsNone, stringResource(R.string.saved_searches), stringResource(R.string.no_saved_searches)) }
        items(searches, key = { "s" + it.id }) { s ->
            WfCard { ListItem(colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), leadingContent = { IconTile(Icons.Outlined.ManageSearch, 36.dp) }, headlineContent = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(stringResource(if (s.notify) R.string.alert_on else R.string.alert_off), style = MaterialTheme.typography.labelSmall) },
                trailingContent = {
                    Row {
                        val state = stringResource(if (s.notify) R.string.alert_on else R.string.alert_off)
                        IconButton({
                            scope.launch { repo.saved.update(s.copy(notify = !s.notify)) }
                            if (!s.notify) enableAlerts(ctx)
                        }, Modifier.semantics { stateDescription = state }) { Icon(Icons.Filled.Notifications, stringResource(R.string.notify_new), tint = if (s.notify) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                        IconButton({ undoable({ repo.saved.remove(s) }, { repo.saved.add(s) }) }) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
                    }
                },
                modifier = Modifier.clickable { openSearch(s.query()) }) }
        }
        if (papers.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.saved_papers), Modifier.padding(top = 12.dp)) }
            items(papers, key = { "p" + it.refId }) { f ->
                ListItem(headlineContent = { Text(f.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { push(Screen.PaperDetail(f.refId.toInt())) })
            }
        }
    }
}

/** Préparation tab: past papers and QCM side by side. */
@Composable
fun PrepScreen(push: (Screen) -> Unit) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    Column {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.tab_papers)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.tab_qcm)) })
        }
        if (tab == 0) PapersScreen(push) else BanksScreen(push)
    }
}
