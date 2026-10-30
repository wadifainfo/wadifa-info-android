@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wadifainfo.concours.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wadifainfo.concours.*
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BanksScreen(push: (Screen) -> Unit) {
    val ctx = LocalContext.current; val lang = lang(); val repo = ConcoursApp.repo(ctx); val scope = rememberCoroutineScope()
    LaunchedEffect(lang) { repo.ensureBuiltInBanks(ctx, lang) }
    val banks by remember(lang) { repo.quiz.banks(lang) }.collectAsState(emptyList())
    val attempts by repo.quiz.attempts().collectAsState(emptyList())
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = try {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                val imp = Quiz.parseBank(text); repo.importBank(imp)
                latinDigits(ctx.getString(R.string.imported, imp.questions.size))
            } catch (e: Exception) { ctx.getString(R.string.import_failed, e.message ?: "?") }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (banks.isEmpty()) item { Spinner() }
        items(banks, key = { it.id }) { b ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (lang == "fr") b.titleFr else b.titleAr, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag(stringResource(if (b.kind == "official") R.string.official_q else R.string.practice),
                            if (b.kind == "official") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
                        Tag(text(R.string.questions, b.questionCount))
                    }
                    Text(b.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ push(Screen.Run(b.id, exam = false)) }) { Text(stringResource(R.string.practice_mode)) }
                        OutlinedButton({ push(Screen.Run(b.id, exam = true)) }) { Text(stringResource(R.string.exam_mode)) }
                    }
                    TextButton({ push(Screen.Run(b.id, exam = false, review = true)) }) { Text(stringResource(R.string.review_wrong)) }
                }
            }
        }
        item { OutlinedButton({ pick.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_bank)) } }
        if (attempts.isNotEmpty()) {
            item { Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(attempts, key = { "a" + it.id }) { a ->
                val pct = if (a.total == 0) 0 else a.score * 100 / a.total
                Text("${fmtDate(java.time.Instant.ofEpochMilli(a.finishedAt).toString(), lang)} · ${banks.firstOrNull { it.id == a.bankId }?.let { if (lang == "fr") it.titleFr else it.titleAr } ?: a.bankId} · " +
                    text(R.string.score, a.score, a.total, pct), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Answers survive recreation (language switch, process death) as "index:questionId" strings. */
private val AnswersSaver = listSaver<SnapshotStateMap<String, Int>, String>(
    save = { m -> m.map { "${it.value}:${it.key}" } },
    restore = { l -> mutableStateMapOf<String, Int>().apply { l.forEach { s -> s.split(':', limit = 2).let { (v, k) -> put(k, v.toInt()) } } } })

/**
 * Practice = instant feedback per question; exam = timed (1 min per question), feedback only at the end.
 * The drawn questions (ids, in order), the answers, the position and whether the run is finished are all saveable: a
 * language switch or process death resumes the same run with the same clock, never a reshuffled one that would leak
 * answers or restart the timer.
 */
@Composable
fun QuizScreen(run: Screen.Run, onClose: () -> Unit) {
    val ctx = LocalContext.current; val repo = ConcoursApp.repo(ctx); val scope = rememberCoroutineScope()
    var ids by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var qs by remember { mutableStateOf<List<Question>?>(null) }
    LaunchedEffect(run) {
        qs = withContext(Dispatchers.IO) {
            ids?.let { saved -> repo.quiz.questionsByIds(saved).associateBy { it.id }.let { m -> saved.mapNotNull { m[it] } } }
                ?: (if (run.review) repo.quiz.toReview(run.bankId) else repo.quiz.questions(run.bankId))
                    .shuffled().take(if (run.exam) 20 else 50).also { ids = ArrayList(it.map { q -> q.id }) }
        }
    }
    val answers = rememberSaveable(saver = AnswersSaver) { mutableStateMapOf<String, Int>() }
    var i by rememberSaveable { mutableIntStateOf(0) }
    var done by rememberSaveable { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    // Leaving a run in progress loses it: ask first (system Back and the top-bar arrow both come through here).
    BackHandler(enabled = !done && answers.isNotEmpty()) { leave = true }
    if (leave) AlertDialog({ leave = false }, title = { Text(stringResource(R.string.leave_quiz_title)) },
        text = { Text(stringResource(R.string.leave_quiz_text)) },
        confirmButton = { TextButton({ leave = false; onClose() }) { Text(stringResource(R.string.leave)) } },
        dismissButton = { TextButton({ leave = false }) { Text(stringResource(R.string.keep_going)) } })
    val list = qs ?: return Spinner()
    if (list.isEmpty()) { Text(stringResource(R.string.nothing_to_review), Modifier.padding(24.dp)); return }
    val result = if (done) remember(list) { Quiz.score(list, answers) } else null
    // Timed mode counts down to a fixed END time (saved across recreation, e.g. a language switch), so the clock never
    // drifts and keeps running honestly while the app is in the background, as a real exam would.
    val started = androidx.compose.runtime.saveable.rememberSaveable { System.currentTimeMillis() }
    val endAt = androidx.compose.runtime.saveable.rememberSaveable { started + list.size * 60_000L }
    var secondsLeft by remember { mutableIntStateOf(((endAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)) }

    fun finish() {
        if (done) return
        val r = Quiz.score(list, answers); done = true
        scope.launch(Dispatchers.IO) {
            val id = repo.quiz.insertAttempt(Attempt(bankId = run.bankId, mode = if (run.exam) "exam" else "practice",
                startedAt = started, finishedAt = System.currentTimeMillis(), score = r.score, total = r.total))
            repo.quiz.insertAnswers(list.map { Answer(id, it.id, answers[it.id] ?: -1, answers[it.id] == it.correctIndex) })
        }
    }
    if (run.exam) LaunchedEffect(Unit) {
        while (!done) {
            secondsLeft = ((endAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            if (secondsLeft == 0) break
            delay(500)
        }
        finish()
    }

    result?.let { r -> return ResultView(list, answers, r, timeUp = run.exam && secondsLeft == 0, onClose) }
    val q = list[i]; val chosen = answers[q.id]
    val reveal = !run.exam && chosen != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}/${list.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (run.exam) Text(text(R.string.time_left, "%d:%02d".format(secondsLeft / 60, secondsLeft % 60)), style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator({ (i + 1f) / list.size }, Modifier.fillMaxWidth())
        Provenance(q)
        Text(q.stem, style = MaterialTheme.typography.titleMedium)
        q.options.forEachIndexed { k, opt ->
            val color = when {
                reveal && k == q.correctIndex -> MaterialTheme.colorScheme.primaryContainer
                reveal && k == chosen -> MaterialTheme.colorScheme.errorContainer
                chosen == k -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
            Surface(color = color, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
                .selectable(chosen == k, enabled = !reveal) { answers[q.id] = k }) {
                Text(opt, Modifier.padding(14.dp))
            }
        }
        if (reveal) Explanation(q)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (i < list.lastIndex) Button({ i++ }, enabled = run.exam || chosen != null) { Text(stringResource(R.string.next)) }
            OutlinedButton({ finish() }) { Text(stringResource(R.string.finish)) }
        }
    }
}

@Composable
private fun Provenance(q: Question) = FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Tag(stringResource(if (q.kind == "official") R.string.official_q else R.string.practice),
        if (q.kind == "official") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
    if (q.subject.isNotBlank()) Tag(q.subject)
}

@Composable
private fun Explanation(q: Question) {
    val ctx = LocalContext.current
    q.explanation?.let {
        Text(stringResource(when (q.explanationKind) { "official" -> R.string.expl_official; "ai" -> R.string.expl_ai; else -> R.string.expl_editorial }),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    q.sourceUrl?.let { u -> Text(q.source, Modifier.clickable { open(ctx, u) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun ResultView(list: List<Question>, answers: Map<String, Int>, r: Quiz.Result, timeUp: Boolean, onClose: () -> Unit) =
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(text(R.string.score, r.score, r.total, r.percent), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        if (timeUp) item { Text(stringResource(R.string.time_up), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
        item { Button(onClose) { Text(stringResource(R.string.back)) } }
        items(list.filter { it.id in r.wrong }, key = { it.id }) { q ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Provenance(q)
                    Text(q.stem, fontWeight = FontWeight.SemiBold)
                    Text("✗ " + (answers[q.id]?.let { q.options.getOrNull(it) } ?: stringResource(R.string.unanswered)), color = MaterialTheme.colorScheme.error)
                    Text("✓ " + q.options[q.correctIndex], color = MaterialTheme.colorScheme.primary)
                    Explanation(q)
                }
            }
        }
    }
