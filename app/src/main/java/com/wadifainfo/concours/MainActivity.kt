package com.wadifainfo.concours

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import com.wadifainfo.concours.data.Query
import com.wadifainfo.concours.ui.*
import kotlinx.coroutines.launch

/**
 * Screens are a simple back stack per tab (no navigation library), same as the Ratibi app. Serializable so the stacks
 * survive activity recreation (language switch) and process death: the user comes back to the same screen.
 */
sealed interface Screen : java.io.Serializable {
    data object Home : Screen
    data class Search(val initial: Query? = null) : Screen
    data class OfferDetail(val id: Long) : Screen
    data object Saved : Screen
    data object Prep : Screen
    data class PaperDetail(val id: Int) : Screen
    data class Run(val bankId: String, val exam: Boolean, val review: Boolean = false) : Screen
    data object Profile : Screen
    data class Pdf(val url: String, val label: String) : Screen
}

/** Offer id from a shared website link: /{id}/slug or /fr/{id}/slug. */
fun offerIdFromLink(uri: Uri?): Long? = offerIdFromPath(uri?.host, uri?.pathSegments)

fun offerIdFromPath(host: String?, segments: List<String>?): Long? {
    if (host == null || !(host == "wadifa-info.com" || host.endsWith(".wadifa-info.com")) || segments == null) return null
    val seg = if (segments.firstOrNull() == "fr") segments.drop(1) else segments
    return seg.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 }
}

class MainActivity : AppCompatActivity() {
    private val link = mutableStateOf<Long?>(null)
    private val tabLink = mutableStateOf<Int?>(null)

    /** Launcher shortcuts use wadifainfo://tab/{index}. */
    private fun route(data: Uri?) {
        offerIdFromLink(data)?.let { link.value = it }
        if (data?.scheme == "wadifainfo" && data.host == "tab") tabLink.value = data.lastPathSegment?.toIntOrNull()?.takeIf { it in 0..4 }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Arabic UI is the default resource set; when the phone is in neither ar nor fr, make the APP language Arabic so
        // layout (RTL), plural rules and dates all follow it. Must run AFTER super.onCreate: before it AppCompat has no
        // delegate to route the call through and silently drops it (strings looked Arabic, plurals followed English).
        if (AppCompatDelegate.getApplicationLocales().isEmpty &&
            ConfigurationCompat.getLocales(resources.configuration)[0]?.language !in setOf("ar", "fr"))
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        // Relative times ("il y a 5 min") and date formats follow the JVM default: align it with the app language.
        ConfigurationCompat.getLocales(resources.configuration)[0]?.let { java.util.Locale.setDefault(it) }
        // The background check has no activity: it reads the language chosen here to write its notifications.
        ConcoursApp.repo(this).prefs.uiLang = if (ConfigurationCompat.getLocales(resources.configuration)[0]?.language == "fr") "fr" else "ar"
        if (savedInstanceState == null) route(intent?.data)
        setContent { AppTheme { RtlForArabic { Surface(Modifier.fillMaxSize()) { App(link, tabLink) } } } }
        Ads.start(this)   // consent check first; ads never block or delay the UI
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent.data)
    }
}

/** Arabic text must lay out RTL even when the phone itself is in English (Arabic is the default resource set). */
@Composable fun RtlForArabic(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides if (lang() == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)

/** "ar" or "fr", following the per-app locale. */
@Composable fun lang(): String = if (ConfigurationCompat.getLocales(LocalConfiguration.current)[0]?.language == "fr") "fr" else "ar"

/** Opens an external link. URLs come from API data: only http(s) is allowed (no intent:, javascript:, file:…). */
fun open(ctx: Context, url: String) = runCatching {
    val u = Uri.parse(url.trim())
    if (u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrBlank())
        ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE))
}

/** One snackbar for the whole app: undo after a removal, failures of local saves. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Notification permission state, shared so Profile can explain a refusal. */
class NotifState { var denied by mutableStateOf(false) }
val LocalNotifState = staticCompositionLocalOf { NotifState() }
private object NotifRequest { var launch: (() -> Unit)? = null }

/** Asks for the Android 13+ notification permission when a feature that notifies is switched on (follow, alerts). */
fun askNotificationPermission() { NotifRequest.launch?.invoke() }

/**
 * Follow / unfollow an offer from any card or screen. The UI follows the database flow, so it updates as soon as the row
 * is written; turning it on asks for the notification permission (reminders), and a failed local save is said, not hidden.
 */
@Composable
fun rememberFollow(): (com.wadifainfo.concours.data.Offer, Boolean) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current; val scope = rememberCoroutineScope(); val sb = LocalSnackbar.current
    val failed = stringResource(R.string.save_failed)
    return remember(ctx, failed) { { o, on ->
        scope.launch { if (!ConcoursApp.repo(ctx).toggleFavorite(o, on)) sb.showSnackbar(failed) }
        if (on) askNotificationPermission()
    } }
}

/** Turns alerts on (opt-in) and asks for the Android 13+ notification permission if needed. */
fun enableAlerts(ctx: Context) {
    val p = ConcoursApp.repo(ctx).prefs
    p.alerts = true
    ConcoursApp.ensureWork(ctx)
    NotifRequest.launch?.invoke()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(link: MutableState<Long?>, tabLink: MutableState<Int?>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val roots = listOf<Screen>(Screen.Home, Screen.Search(), Screen.Saved, Screen.Prep, Screen.Profile)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stacks = rememberSaveable(saver = listSaver<List<SnapshotStateList<Screen>>, ArrayList<Screen>>(
        save = { all -> all.map { ArrayList(it) } },
        restore = { saved -> saved.map { mutableStateListOf(*it.toTypedArray()) } })) { roots.map { mutableStateListOf(it) } }
    val stack = stacks[tab]
    val top = stack.last()
    // Each stack entry keeps its saveable state (scroll positions, typed text) while it is covered by another screen or
    // its tab is not shown; popping an entry drops its state.
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun keyOf(t: Int, i: Int) = "$t/$i/${stacks[t][i]}"
    fun pop() { holder.removeState(keyOf(tab, stack.lastIndex)); stack.removeAt(stack.lastIndex) }
    val snackbar = remember { SnackbarHostState() }
    val push: (Screen) -> Unit = { s ->
        when (s) {
            Screen.Profile -> tab = 4
            Screen.Prep -> tab = 3
            else -> stack.add(s)
        }
    }
    val openSearch: (Query) -> Unit = { q -> tab = 1; while (stacks[1].size > 1) { holder.removeState(keyOf(1, stacks[1].lastIndex)); stacks[1].removeAt(stacks[1].lastIndex) }
        stacks[1][0] = Screen.Search(q) }
    BackHandler(stack.size > 1 || tab != 0) { if (stack.size > 1) pop() else tab = 0 }
    // The top bar arrow goes through the same dispatcher as the system Back, so screens that guard leaving (an exam in
    // progress) see both.
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val notif = remember { NotifState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notif.denied = !granted }
    DisposableEffect(Unit) {
        NotifRequest.launch = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onDispose { NotifRequest.launch = null }
    }
    // Website links (shared offers, notifications) open the offer inside the app.
    LaunchedEffect(link.value) { link.value?.let { tab = 0; stacks[0].add(Screen.OfferDetail(it)); link.value = null } }
    LaunchedEffect(tabLink.value) { tabLink.value?.let { tab = it; tabLink.value = null } }

    CompositionLocalProvider(LocalNotifState provides notif, LocalSnackbar provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // Home: the bar continues the green hero; elsewhere a plain surface bar.
                val onHome = top is Screen.Home
                val cs = MaterialTheme.colorScheme
                val dark = isSystemInDarkTheme()
                val barColor = if (onHome) heroColor() else if (top is Screen.OfferDetail) cs.primaryContainer else cs.surface
                val barInk = if (onHome) heroInk() else if (top is Screen.OfferDetail) cs.onPrimaryContainer else cs.onSurface
                // Status-bar icons follow the bar actually drawn behind them (dark icons on light bars, light on dark).
                val view = androidx.compose.ui.platform.LocalView.current
                SideEffect {
                    (view.context as? android.app.Activity)?.window?.let { w ->
                        androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = barColor.luminance() > .5f
                    }
                }
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (stack.size == 1) Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.ic_fg), null, Modifier.size(30.dp))
                        }
                        Text(if (top is Screen.Pdf) top.label else stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }, navigationIcon = {
                    if (stack.size > 1) IconButton({ backDispatcher?.onBackPressed() ?: pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                }, colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barColor, titleContentColor = barInk, navigationIconContentColor = barInk))
            },
            bottomBar = {
                // Filled icon = current tab, outlined otherwise; hairline on top instead of a shadow.
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                        listOf(Triple(Icons.Filled.Home, Icons.Outlined.Home, R.string.tab_home), Triple(Icons.Filled.Search, Icons.Outlined.Search, R.string.tab_search),
                            Triple(Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks, R.string.tab_saved), Triple(Icons.Filled.MenuBook, Icons.Outlined.MenuBook, R.string.tab_prep_short),
                            Triple(Icons.Filled.Person, Icons.Outlined.Person, R.string.tab_profile)).forEachIndexed { i, (on, off, label) ->
                            NavigationBarItem(selected = tab == i, onClick = { if (tab == i) while (stack.size > 1) pop() else tab = i },
                                icon = { Icon(if (tab == i) on else off, null) }, label = { Text(stringResource(label), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedTextColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { holder.SaveableStateProvider(keyOf(tab, stack.lastIndex)) {
                    when (top) {
                        Screen.Home -> HomeScreen(push, openSearch)
                        is Screen.Search -> SearchScreen(top.initial, push)
                        is Screen.OfferDetail -> key(top.id) { OfferDetailScreen(top.id, push) }
                        Screen.Saved -> SavedScreen(push, openSearch)
                        Screen.Prep -> PrepScreen(push)
                        is Screen.PaperDetail -> key(top.id) { PaperDetailScreen(top.id) }
                        is Screen.Run -> QuizScreen(top) { pop() }
                        Screen.Profile -> ProfileScreen()
                        is Screen.Pdf -> key(top.url) { PdfScreen(top.url, top.label) }
                    }
                } }
            }
        }
    }
}
