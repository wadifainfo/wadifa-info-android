@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wadifainfo.concours.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wadifainfo.concours.*
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.*
import kotlinx.coroutines.launch

@Composable
fun OfferDetailScreen(id: Long, push: (Screen) -> Unit) {
    val ctx = LocalContext.current; val lang = lang(); val repo = ConcoursApp.repo(ctx); val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<Parse.Detail>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, lang, attempt) {
        state = Load.Loading
        state = try { repo.offer(id, lang).let { Load.Ok(it.data, it.offline, it.syncedAt) } } catch (e: Exception) { Load.Err(e.message) }
    }
    val fav by repo.favorites.isFav("offer", id.toString()).collectAsState(0)
    val follow = rememberFollow()
    when (val s = state) {
        Load.Loading -> SkeletonList(3, Modifier.padding(16.dp))
        is Load.Err -> ErrorBox { attempt++ }
        is Load.Ok -> {
            val (o, papers, related) = s.data
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Header(o) }
                    if (s.offline) item { Box(Modifier.padding(horizontal = 16.dp)) { OfflineBanner(s.syncedAt) { attempt++ } } }
                    item { Box(Modifier.padding(horizontal = 16.dp)) { FactGrid(o, lang) } }
                    item {
                        Block(R.string.profile_required, Icons.Outlined.School) {
                            Line(R.string.diploma, o.diplomas.joinToString(" · ").ifBlank { null })
                            Line(R.string.specialty, o.specialties.joinToString(" · ").ifBlank { null })
                            if (o.diplomas.isEmpty() && o.specialties.isEmpty()) Muted(stringResource(R.string.see_notice_for_profile))
                        }
                    }
                    o.infos?.let { item { Block(R.string.conditions, Icons.Outlined.Info) { Text(it, style = MaterialTheme.typography.bodyMedium) } } }
                    // How to apply: official destinations only.
                    item {
                        Block(R.string.how_to_apply, if (o.electronic) Icons.Outlined.Language else Icons.Outlined.LocalPostOffice) {
                            Text(stringResource(if (o.electronic) R.string.apply_online_text else R.string.apply_paper_text), style = MaterialTheme.typography.bodyMedium)
                            o.deposit?.let { Muted(it) }
                            o.applyOnline?.let { Button({ open(ctx, it) }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.apply)) } }
                            o.applyAdmin?.let { if (it != o.applyOnline) OutlinedButton({ open(ctx, it) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.apply_admin)) } }
                            o.officialNotice?.let { OutlinedButton({ open(ctx, it) }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Verified, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.official_notice)) } }
                            o.employerUrl?.let { TextButton({ open(ctx, it) }) { Text(stringResource(R.string.employer_site)) } }
                        }
                    }
                    if (o.documents.isNotEmpty()) item {
                        Block(R.string.documents, Icons.Outlined.Description) {
                            o.documents.forEachIndexed { i, d ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { push(Screen.Pdf(d.url, d.label ?: o.grade)) }
                                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.Center) {
                                        Text("PDF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(d.label ?: stringResource(R.string.document), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(stringResource(R.string.official_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    } else if (!s.offline && o.detailLoaded) item { Box(Modifier.padding(horizontal = 16.dp)) { Muted(stringResource(R.string.no_documents)) } }
                    if (papers.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.related_papers), Icons.Outlined.MenuBook, Modifier.padding(horizontal = 16.dp)) }
                        items(papers, key = { "p" + it.id }) { p -> Box(Modifier.padding(horizontal = 16.dp)) { PaperCard(p, lang) { push(Screen.PaperDetail(p.id)) } } }
                    }
                    if (related.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.related_offers), Icons.Outlined.WorkOutline, Modifier.padding(horizontal = 16.dp)) }
                        items(related, key = { "o" + it.id }) { r -> Box(Modifier.padding(horizontal = 16.dp)) { OfferCard(r, lang) { push(Screen.OfferDetail(r.id)) } } }
                    }
                    // Clear separation between official facts and Wadifa Info's page.
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Muted(stringResource(R.string.disclaimer_offer))
                            o.updated?.let { Muted(text(R.string.checked_on, fmtDate(it, lang) ?: it)) }
                            TextButton({ open(ctx, o.url) }) { Text(stringResource(R.string.open_site)) }
                        }
                    }
                }
                // Sticky actions: follow (= deadline reminders) and share stay one thumb away.
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (fav > 0) FilledTonalButton({ follow(o, false) }, Modifier.weight(1f)) {
                            Icon(Icons.Filled.Bookmark, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.followed))
                        } else Button({ follow(o, true) }, Modifier.weight(1f)) {
                            Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.follow))
                        }
                        OutlinedButton({ share(ctx, o) }) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share))
                        }
                    }
                }
            }
        }
    }
}

/** Title block on a soft brand tint: employer badge, grade, employer, status + type chips. */
@Composable
private fun Header(o: Offer) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)).background(cs.primaryContainer)
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(o.employer, 48.dp, o.employerType)
            o.employer?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Text(o.grade, style = MaterialTheme.typography.headlineSmall, color = cs.onPrimaryContainer)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(o.deadline)
            o.recruitmentType?.let { Tag(it, cs.surfaceContainerLowest, cs.onSurface) }
            o.employerType?.let { Tag(stringResource(etypeLabel(it)), cs.surfaceContainerLowest, cs.onSurface) }
            if (o.electronic) Tag(stringResource(R.string.online_deposit), cs.surfaceContainerLowest, cs.onSurface, Icons.Outlined.Language)
        }
    }
}

/**
 * Key facts as tiles. The three dates stay separate and labelled: users of the official app confuse the deposit
 * deadline with the exam date.
 */
@Composable
private fun FactGrid(o: Offer, lang: String) {
    val facts = listOfNotNull(
        Triple(Icons.Outlined.EventAvailable, R.string.deadline, fmtDate(o.deadline, lang) ?: stringResource(R.string.not_announced)),
        Triple(Icons.Outlined.EditCalendar, R.string.exam_date, fmtDate(o.examDate, lang) ?: stringResource(R.string.not_announced)),
        o.posts?.let { Triple(Icons.Outlined.Groups, R.string.posts, it.toString()) },
        listOfNotNull(o.city, o.region).distinct().joinToString(" · ").ifBlank { null }?.let { Triple(Icons.Outlined.Place, R.string.region, it) },
        salaryText(o)?.let { Triple(Icons.Outlined.Payments, if (o.salaryEstimated) R.string.salary_estimated else R.string.salary, it) },
        fmtDate(o.published, lang)?.let { Triple(Icons.Outlined.Today, R.string.published, it) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        facts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { i, (icon, label, value) ->
                    val emph = label == R.string.deadline
                    WfCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(icon, null, Modifier.size(18.dp), tint = if (emph) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = if (emph) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A titled section card. */
@Composable
private fun Block(title: Int, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) =
    WfCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                SectionTitle(stringResource(title))
            }
            content()
        }
    }

@Composable
private fun Line(label: Int, value: String?) {
    if (value == null) return
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

fun share(ctx: android.content.Context, o: Offer) {
    val text = listOfNotNull(o.grade, o.employer, o.deadline?.let { ctx.getString(R.string.deadline) + " : " + it }, o.url).joinToString("\n")
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}
