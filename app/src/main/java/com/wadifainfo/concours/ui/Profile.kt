@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wadifainfo.concours.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.wadifainfo.concours.*
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.Filters
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
    val ctx = LocalContext.current; val lang = lang(); val repo = ConcoursApp.repo(ctx); val p = repo.prefs; val scope = rememberCoroutineScope()
    var filters by remember { mutableStateOf<Filters?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { filters = runCatching { repo.filters() }.onFailure { failed = true }.getOrNull() }
    var diploma by remember { mutableStateOf(p.diploma) }
    var region by remember { mutableStateOf(p.region) }
    var specialty by remember { mutableStateOf(p.specialty ?: "") }
    var alerts by remember { mutableStateOf(p.alerts) }
    var hours by remember { mutableIntStateOf(p.alertHours) }
    var reminders by remember { mutableStateOf(p.reminders) }
    val notifState = LocalNotifState.current
    var confirmClear by remember { mutableStateOf(false) }
    val sb = LocalSnackbar.current; val cleared = stringResource(R.string.history_cleared)
    if (confirmClear) AlertDialog({ confirmClear = false }, title = { Text(stringResource(R.string.clear_history)) },
        text = { Text(stringResource(R.string.clear_history_confirm)) },
        confirmButton = { TextButton({ confirmClear = false; scope.launch { repo.recent.clear(); sb.showSnackbar(cleared) } }) { Text(stringResource(R.string.clear_history)) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } })

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { WfCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader(stringResource(R.string.language), Icons.Outlined.Language)
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ar" to "العربية", "fr" to "Français").forEach { (tag, label) ->
                    FilterChip(lang == tag, { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }, { Text(label) })
                }
            }
        } } }
        item { WfCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader(stringResource(R.string.my_profile), Icons.Outlined.Badge)
            Text(stringResource(R.string.my_profile_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filters?.let { f ->
                    FilterMenu(stringResource(R.string.diploma), f.diplomas, diploma, lang, Modifier.fillMaxWidth()) { diploma = it?.value; p.diploma = it?.value }
                    FilterMenu(stringResource(R.string.region), f.regions, region, lang, Modifier.fillMaxWidth()) { region = it?.value; p.region = it?.value }
                } ?: if (failed) Text(stringResource(R.string.filters_offline), style = MaterialTheme.typography.bodySmall) else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(specialty, { specialty = it; p.specialty = it.trim().ifBlank { null } }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.specialty)) }, placeholder = { Text(stringResource(R.string.specialty_hint)) })
            }
        } } }
        item { WfCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader(stringResource(R.string.notifications), Icons.Outlined.NotificationsActive)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.alerts))
                    Text(stringResource(R.string.alerts_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(alerts, { on -> alerts = on; if (on) enableAlerts(ctx) else { p.alerts = false; ConcoursApp.ensureWork(ctx) } })
            }
            if (alerts) {
                if (notifState.denied) Text(stringResource(R.string.notif_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.frequency), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(12, 24).forEach { h ->
                        FilterChip(hours == h, { hours = h; p.alertHours = h; ConcoursApp.ensureWork(ctx) },
                            { Text(stringResource(if (h == 12) R.string.twice_daily else R.string.daily)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.deadline_reminders), Modifier.weight(1f)); Switch(reminders, { reminders = it; p.reminders = it; ConcoursApp.ensureWork(ctx); if (it) askNotificationPermission() })
                }
                Text(stringResource(R.string.alerts_manage_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } } }
        item { WfCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader(stringResource(R.string.privacy), Icons.Outlined.Shield)
            Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodySmall)
            TextButton({ confirmClear = true }) { Text(stringResource(R.string.clear_history)) }
            if (Ads.privacyOptionsRequired()) TextButton({ (ctx as? android.app.Activity)?.let { Ads.showPrivacyOptions(it) } }) {
                Text(stringResource(R.string.ad_privacy_options))
            }
            TextButton({ open(ctx, "https://www.wadifa-info.com/Content/wadifa-app/privacy-policy.html") }) { Text(stringResource(R.string.privacy_policy)) }
        } } }
        item {
            Text(stringResource(R.string.about), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Wadifa Info " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
