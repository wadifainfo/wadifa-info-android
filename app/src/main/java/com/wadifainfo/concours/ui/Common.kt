@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wadifainfo.concours.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.FilterValue
import com.wadifainfo.concours.data.Logic
import com.wadifainfo.concours.data.Offer
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Morocco writes Arabic with Latin digits (like the website); Android formats %d with Arabic-Indic digits in "ar". */
fun latinDigits(s: String): String = buildString { s.forEach { c -> append(if (c in '٠'..'٩') '0' + (c - '٠') else c) } }

/** stringResource / pluralStringResource with Latin digits. */
@Composable fun text(id: Int, vararg args: Any): String = latinDigits(stringResource(id, *args))
@Composable fun pluralText(id: Int, n: Int, vararg args: Any): String = latinDigits(pluralStringResource(id, n, *args))

/** Loading / error / offline wrapper shared by every screen. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T, val offline: Boolean, val syncedAt: Long = 0) : Load<T>
    data class Err(val message: String?) : Load<Nothing>
}

/* ---------------------------------------------------------------- building blocks */

/** The one card style: white, hairline border, 12dp corners, no elevation (matches the website cards). */
@Composable
fun WfCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, border = border, content = content)
    else Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, border = border, content = content)
}

/** Small icon + text line (meta facts). Icons are decorative: the text carries the meaning. */
@Composable
fun Meta(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, label: String? = null) =
    // [label] is what TalkBack reads when the visible text alone is ambiguous (a bare date: deadline or exam?).
    Row(if (label != null) Modifier.clearAndSetSemantics { contentDescription = label } else Modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

/** Employer family -> icon. Arabic names mostly start with "ال"/"إقليم", so an initial letter tells nothing apart. */
fun employerIcon(type: String?, name: String?): ImageVector {
    val n = (name ?: "").lowercase()
    val t = type ?: when {
        listOf("جماعة", "إقليم", "عمالة", "جهة", "commune", "province", "préfecture", "region", "région").any { it in n } -> "local"
        listOf("وزارة", "المديرية", "ministère", "direction", "المندوبية", "délégation").any { it in n } -> "state"
        else -> "public_body"
    }
    return when (t) { "state" -> Icons.Outlined.AccountBalance; "local" -> Icons.Outlined.LocationCity; else -> Icons.Outlined.Domain }
}

/** Employer badge: family icon on a soft tint picked from the name, so the same administration always looks the same. */
@Composable
fun Avatar(name: String?, size: Dp = 40.dp, type: String? = null) {
    val dark = MaterialTheme.colorScheme.background.red < .5f
    val tints = if (dark) listOf(0xFF1E3A2B, 0xFF26334A, 0xFF4A3218, 0xFF2D2A4A, 0xFF183B3F)
                else listOf(0xFFDCF2E3, 0xFFE3EAF5, 0xFFFFEED6, 0xFFEAE6FA, 0xFFD9F1F2)
    val inks = if (dark) listOf(0xFF8EE0AC, 0xFFB7C6E0, 0xFFF5B66B, 0xFFC7BEF5, 0xFF8AD6DA)
               else listOf(0xFF116632, 0xFF13233F, 0xFF8A4204, 0xFF4B3B9A, 0xFF0E5E63)
    val k = Math.floorMod((name ?: "").hashCode(), tints.size)
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color(tints[k])), contentAlignment = Alignment.Center) {
        Icon(employerIcon(type, name), null, Modifier.size(size * .55f), tint = Color(inks[k]))
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)

@Composable
fun Tag(text: String, container: Color = MaterialTheme.colorScheme.surfaceContainer, content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        icon: ImageVector? = null) =
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon?.let { Icon(it, null, Modifier.size(14.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

/** Empty / error states: icon in a soft circle, a title, one line of help, optional action. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, action: (@Composable () -> Unit)? = null) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        action?.invoke()
    }

@Composable
fun ErrorBox(onRetry: () -> Unit) = EmptyState(Icons.Outlined.CloudOff, stringResource(R.string.error), stringResource(R.string.error_hint)) {
    Button(onRetry) { Text(stringResource(R.string.retry)) }
}

@Composable
fun Spinner() = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) }

/** Placeholder cards shaped like OfferCard while the first page loads (no layout jump when real cards arrive). */
@Composable
fun SkeletonList(n: Int = 5, modifier: Modifier = Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    val hi = MaterialTheme.colorScheme.surfaceContainerHigh; val lo = MaterialTheme.colorScheme.surfaceContainer
    repeat(n) {
        WfCard {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(hi))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(hi))
                    Box(Modifier.fillMaxWidth(0.5f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(lo))
                    Box(Modifier.fillMaxWidth(0.65f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(lo))
                }
            }
        }
    }
}

@Composable
fun OfflineBanner(syncedAt: Long = 0, onRetry: (() -> Unit)? = null) = Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp))
        Text(stringResource(R.string.offline) + (if (syncedAt > 0) " · " + ago(syncedAt) else ""), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        onRetry?.let { TextButton(it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) { Text(stringResource(R.string.retry)) } }
    }
}

/** "à l'instant" / "il y a 5 min" (lower-case: always used mid-sentence). */
@Composable
fun ago(t: Long): String {
    val now = System.currentTimeMillis()
    if (now - t < 60_000) return stringResource(R.string.just_now)
    return DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        .replaceFirstChar { it.lowercase() }
}

/** ISO date from the API → localized medium date ("31 oct. 2026" / "31 أكتوبر 2026"). */
fun fmtDate(iso: String?, lang: String): String? = Logic.parseDate(iso)?.let {
    // ponytail: Arabic month names via ar-MA; digits stay Latin like the website.
    it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(if (lang == "fr") "fr-MA" else "ar-MA-u-nu-latn")))
}

/** Deadline status chip: always present, so "unknown" is explicit instead of a silent gap. Colour = urgency. */
@Composable
fun StatusChip(deadline: String?) {
    val d = Logic.daysLeft(deadline)
    val cs = MaterialTheme.colorScheme
    when (Logic.status(deadline)) {
        Logic.Status.CLOSED -> Tag(stringResource(R.string.st_closed), cs.surfaceContainerHigh, cs.onSurfaceVariant, Icons.Outlined.EventBusy)
        Logic.Status.LAST_DAY -> Tag(stringResource(R.string.st_last_day), cs.errorContainer, cs.onErrorContainer, Icons.Outlined.Alarm)
        Logic.Status.CLOSING -> Tag(pluralText(R.plurals.st_days_left, d!!.toInt(), d.toInt()), cs.errorContainer, cs.onErrorContainer, Icons.Outlined.Alarm)
        Logic.Status.OPEN -> Tag(pluralText(R.plurals.st_days_left, d!!.toInt(), d.toInt()), cs.primaryContainer, cs.onPrimaryContainer, Icons.Outlined.Schedule)
        Logic.Status.UNKNOWN -> Tag(stringResource(R.string.st_unknown), cs.surfaceContainer, cs.onSurfaceVariant, Icons.Outlined.HelpOutline)
    }
}

/**
 * Offer card: employer badge + grade + employer, one wrapped line of facts, then a footer with the deadline status and
 * the deadline date. ~3 cards per phone screen (the official app shows about two — a top complaint).
 */
/** "8 100 – 10 900 DH" / "≈ 8 100 – 10 900 DH" (estimated band). Latin digits, thin-space thousands. */
fun salaryText(o: Offer): String? {
    val mn = o.salaryMin ?: return null
    fun f(v: Int) = "%,d".format(Locale.ROOT, v).replace(',', '\u202F')
    val core = if (o.salaryMax != null && o.salaryMax != mn) "${f(mn)} – ${f(o.salaryMax)} DH" else "${f(mn)} DH"
    return "\u2066" + (if (o.salaryEstimated) "≈ " else "") + core + "\u2069"
}

/**
 * [onFollow] adds a bookmark button in the footer; it is its own tap target (the card's click is not triggered) and is
 * announced as "Follow"/"Following" by TalkBack.
 */
@Composable
fun OfferCard(o: Offer, lang: String, followed: Boolean = false, onFollow: (() -> Unit)? = null, onClick: () -> Unit) = WfCard(onClick = onClick) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(o.employer, type = o.employerType)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(o.grade, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                o.employer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (o.city ?: o.region)?.let { Meta(Icons.Outlined.Place, it) }
            o.posts?.let { Meta(Icons.Outlined.Groups, pluralText(R.plurals.posts_n, it, it)) }
            (o.specialties.firstOrNull() ?: o.diplomas.firstOrNull())?.let { Meta(Icons.Outlined.School, it) }
            salaryText(o)?.let { Meta(Icons.Outlined.Payments, it) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(o.deadline)
            Spacer(Modifier.weight(1f))
            val due = fmtDate(o.deadline, lang)
            if (due != null) Meta(Icons.Outlined.Event, due, label = text(R.string.deadline_on, due))
            else fmtDate(o.published, lang)?.let { Meta(Icons.Outlined.Today, text(R.string.published_on, it)) }
            if (onFollow != null) {
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                IconButton({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onFollow() },
                    Modifier.padding(start = 4.dp).size(40.dp)) {
                    Icon(if (followed) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd,
                        stringResource(if (followed) R.string.followed else R.string.follow),
                        tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterMenu(label: String, values: List<FilterValue>, selected: String?, lang: String, modifier: Modifier, onPick: (FilterValue?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun FilterValue.label() = if (lang == "fr") fr else ar
    val sel = values.firstOrNull { it.value == selected }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(sel?.label() ?: selected ?: stringResource(R.string.any), {}, readOnly = true, singleLine = true, label = { Text(label) },
            shape = MaterialTheme.shapes.medium,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.any)) }, { onPick(null); open = false })
            values.forEach { v -> DropdownMenuItem({ Text(v.label()) }, { onPick(v); open = false }) }
        }
    }
}
