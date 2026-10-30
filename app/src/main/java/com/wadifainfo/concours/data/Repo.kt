package com.wadifainfo.concours.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Network first, Room cache as fallback (offline). Server ids are the keys, so a refresh never duplicates rows. */
class Repo(ctx: Context) {
    private val app = ctx.applicationContext
    private val db = AppDb.get(ctx)
    val api = WadifaApi(ctx.cacheDir)
    val prefs = Prefs(ctx)
    val docs = Docs(ctx, db.downloads())

    /** offline = served from the phone's cache; syncedAt = when that data last came from the server. */
    data class Loaded<T>(val data: T, val offline: Boolean, val syncedAt: Long = System.currentTimeMillis())

    suspend fun offers(lang: String, query: Query, page: Int): Loaded<Page<Offer>> = withContext(Dispatchers.IO) {
        try {
            val p = api.searchOffers(lang, query, page)
            // Keep richer detail rows already cached (list rows carry no documents).
            db.offers().upsert(p.items.map { o -> db.offers().byId(o.id, lang)?.takeIf { it.detailLoaded }?.let { d -> o.withDetailOf(d) } ?: o })
            if (page == 1 && query.isEmpty) prefs.setLastSync(lang, System.currentTimeMillis())
            Loaded(p, false)
        } catch (e: Exception) {
            if (page > 1) throw e
            val f = filtersCache
            val cached = Paging.filterLocal(db.offers().latest(lang, 500), query, f).let { Paging.sortLocal(it, query.sort) }
            if (cached.isEmpty()) throw e
            Loaded(Page(1, cached.size, cached.size, cached), true, cached.maxOf { it.syncedAt })
        }
    }

    // Offer details seen in the last few minutes: Back from a PDF or a related offer redraws instantly, no refetch.
    private val details = android.util.LruCache<String, Pair<Long, Parse.Detail>>(24)

    suspend fun offer(id: Long, lang: String): Loaded<Parse.Detail> = withContext(Dispatchers.IO) {
        val hit = details.get("$lang:$id")?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }
        val result = if (hit != null) Loaded(hit.second, false, hit.first) else try {
            val d = api.offer(id, lang)
            details.put("$lang:$id", System.currentTimeMillis() to d)
            db.offers().upsert(listOf(d.offer)); db.papers().upsert(d.papers.map { r -> db.papers().byId(r.id, lang)?.takeIf { it.detailLoaded } ?: r })
            Loaded(d, false)
        } catch (e: Exception) {
            val o = db.offers().byId(id, lang) ?: throw e
            Loaded(Parse.Detail(o, emptyList(), emptyList()), true, o.syncedAt)
        }
        val o = result.data.offer
        db.recent().add(Recent(o.id, o.grade, o.employer, System.currentTimeMillis())); db.recent().trim()
        // A followed offer's deadline can be extended by a correction notice: reminders must use the current one.
        if (!result.offline) db.favorites().get("offer", o.id.toString())?.let { f ->
            if (f.deadline != o.deadline || f.title != o.grade) db.favorites().add(f.copy(deadline = o.deadline, title = o.grade, employer = o.employer))
        }
        result
    }

    suspend fun suggest(lang: String, q: String): List<Parse.Suggestion> = withContext(Dispatchers.IO) {
        if (q.trim().length < 2) emptyList() else runCatching { api.suggest(lang, q.trim()) }.getOrDefault(emptyList())
    }

    suspend fun papers(lang: String, q: String?, page: Int): Loaded<Page<Paper>> = withContext(Dispatchers.IO) {
        try {
            val p = api.papers(lang, q, page)
            db.papers().upsert(p.items.map { r -> db.papers().byId(r.id, lang)?.takeIf { it.detailLoaded } ?: r })
            Loaded(p, false)
        } catch (e: Exception) {
            if (page > 1) throw e
            val cached = db.papers().all(lang).filter { q.isNullOrBlank() || Paging.matches(q, it.title, it.employer, it.grade, it.specialty) }
            if (cached.isEmpty()) throw e
            Loaded(Page(1, cached.size, cached.size, cached), true)
        }
    }

    suspend fun paper(id: Int, lang: String): Loaded<Paper> = withContext(Dispatchers.IO) {
        try { api.paper(id, lang).also { db.papers().upsert(listOf(it)) }.let { Loaded(it, false) } }
        catch (e: Exception) { Loaded(db.papers().byId(id, lang) ?: throw e, true) }
    }

    @Volatile private var filtersCache: Filters? = null
    suspend fun filters(): Filters = withContext(Dispatchers.IO) { filtersCache ?: api.filters().also { filtersCache = it } }

    val quiz get() = db.quiz()
    val favorites get() = db.favorites()
    val saved get() = db.saved()
    val recent get() = db.recent()
    val notified get() = db.notified()

    /** false when the local write failed (the caller tells the user). */
    suspend fun toggleFavorite(o: Offer, on: Boolean): Boolean = withContext(Dispatchers.IO) { runCatching {
        if (on) db.favorites().add(Favorite("offer", o.id.toString(), o.grade, System.currentTimeMillis(), o.deadline, o.employer))
        else db.favorites().remove("offer", o.id.toString())
        followChanged()
    }.isSuccess }

    /** Remove / put back a followed offer or paper exactly as it was (Followed screen, with undo). */
    suspend fun removeFavorite(f: Favorite) = withContext(Dispatchers.IO) { db.favorites().remove(f.type, f.refId); followChanged() }
    suspend fun restoreFavorite(f: Favorite) = withContext(Dispatchers.IO) { db.favorites().add(f); followChanged() }

    // Unfollowing needs no cancellation: reminders are derived from the favorites table on every run; the periodic check
    // itself is kept only while something needs it.
    private suspend fun followChanged() {
        prefs.hasFollowed = db.favorites().offerCount() > 0
        com.wadifainfo.concours.ConcoursApp.ensureWork(app)
    }

    suspend fun importBank(imp: Quiz.Imported) = withContext(Dispatchers.IO) {
        db.quiz().clearBank(imp.bank.id); db.quiz().upsertQuestions(imp.questions); db.quiz().upsertBank(imp.bank)
    }

    /** Built-in banks: JSON files in assets/qcm (the website's QCM trainers, tools/site_qcm.py), validated like any import. */
    suspend fun ensureBuiltInBanks(ctx: Context, lang: String) = withContext(Dispatchers.IO) {
        ctx.assets.list("qcm")?.filter { it.endsWith(".json") }?.forEach { f ->
            runCatching { Quiz.parseBank(ctx.assets.open("qcm/$f").bufferedReader().readText()) }.getOrNull()?.let { imp ->
                if ((db.quiz().bank(imp.bank.id)?.version ?: 0) < imp.bank.version) importBank(imp)
            }
        }
        // 2.3: the generated salary quiz was replaced by the site's real trainers; remove it from older installs
        for (l in listOf("fr", "ar")) if (db.quiz().bank("salaires-$l") != null) { db.quiz().clearBank("salaires-$l"); db.quiz().deleteBank("salaires-$l") }
    }
}

private fun Offer.withDetailOf(d: Offer) = copy(noticePdf = d.noticePdf, applyOnline = d.applyOnline, applyAdmin = d.applyAdmin,
    employerUrl = d.employerUrl, deposit = d.deposit, infos = d.infos, documents = d.documents, detailLoaded = true)

/** Offline filtering/sorting of cached offers, mirroring the server's rules as closely as the cached fields allow. */
object Paging {
    private fun norm(s: String) = s.lowercase()
        .replace(Regex("[إأآا]"), "ا").replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("[éèêë]"), "e").replace(Regex("[àâ]"), "a").replace(Regex("[ôö]"), "o").replace(Regex("[îï]"), "i").replace(Regex("[ûü]"), "u").replace('ç', 'c')

    fun matches(q: String, vararg fields: String?): Boolean {
        val hay = norm(fields.filterNotNull().joinToString(" "))
        return norm(q).split(Regex("\\s+")).filter { it.length >= 2 }.all { hay.contains(it) }
    }

    /** Cached offers carry display labels (AR or FR), so a diploma/region value matches any of its labels. */
    fun filterLocal(all: List<Offer>, q: Query, f: Filters?, today: java.time.LocalDate = Logic.today()) = all.filter { o ->
        fun labels(v: String, list: List<FilterValue>?) = listOfNotNull(v) + (list?.firstOrNull { it.value == v }?.let { listOf(it.ar, it.fr) } ?: emptyList())
        (q.q.isNullOrBlank() || matches(q.q, o.grade, o.employer, o.specialties.joinToString(" "), o.city, o.region)) &&
        (q.diplome == null || labels(q.diplome, f?.diplomas).any { it in o.diplomas }) &&
        (q.specialite == null || matches(q.specialite, o.specialties.joinToString(" "), o.grade)) &&
        (q.region == null || labels(q.region, f?.regions).any { (o.region ?: "").contains(it) }) &&
        (q.city == null || matches(q.city, o.city)) &&
        (q.contract == null || Logic.contractKind(o.recruitmentType) == q.contract) &&
        (q.etype == null || o.employerType == q.etype) &&
        (!q.electronic || o.electronic) &&
        (q.includeClosed || Logic.status(o.deadline, today) != Logic.Status.CLOSED)
    }

    fun sortLocal(list: List<Offer>, sort: Int): List<Offer> = when (sort) {
        5 -> list.sortedWith(compareBy<Offer> { Logic.parseDate(it.deadline) ?: java.time.LocalDate.MAX }.thenByDescending { it.id })
        3 -> list.sortedByDescending { it.posts ?: 0 }
        2 -> list.sortedByDescending { it.salaryMax ?: it.salaryMin ?: 0 }
        else -> list.sortedWith(compareByDescending<Offer> { it.published ?: "" }.thenByDescending { it.id })
    }

    /** Appends a new page, keeping first occurrence of each id (pages can shift while new offers arrive). */
    fun <T> merge(existing: List<T>, next: List<T>, key: (T) -> Any): List<T> {
        val seen = existing.map(key).toHashSet()
        return existing + next.filter { seen.add(key(it)) }
    }
}
