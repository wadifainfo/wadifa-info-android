package com.wadifainfo.concours.data

import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** Public, read-only Wadifa Info API (no key). Responses are cached server-side and in a small HTTP cache here. */
class WadifaApi(cacheDir: File?, private val base: String = BASE) {
    companion object { const val BASE = "https://www.wadifa-info.com"; const val MAX_RETRY = 2 }

    // Read timeout 45 s: a cold (uncached) search was measured at 21 s on 2026-10-14 (docs/API_AUDIT.md).
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .apply { if (cacheDir != null) cache(Cache(File(cacheDir, "http"), 10L * 1024 * 1024)) }
        .addInterceptor { c -> c.proceed(c.request().newBuilder().header("User-Agent", "WadifaInfo-Android/2.2").build()) }
        .build()

    /**
     * Every call is an idempotent GET, so transient failures are retried: network errors, 5xx and 429 (Retry-After
     * honoured), at most [MAX_RETRY] times with backoff. 404 / bad JSON / API "error" bodies are final.
     */
    private fun get(path: String, params: Map<String, Any?>): JSONObject {
        val url = (base + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (v != null && v.toString().isNotBlank()) addQueryParameter(k, v.toString()) }
        }.build()
        var attempt = 0
        while (true) {
            val outcome = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if ((r.code == 429 || r.code >= 500) && attempt < MAX_RETRY) {
                        Thread.sleep(Logic.backoffMillis(attempt, r.header("Retry-After"))); null
                    } else {
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw ApiException(r.code, if (r.code == 404) "not_found" else "http_${r.code}")
                        val json = try { JSONObject(body) } catch (e: org.json.JSONException) { throw ApiException(502, "bad_json") }
                        if (json.has("error")) throw ApiException(503, json.optString("error"))
                        json
                    }
                }
            }
            outcome.getOrNull()?.let { return it }
            outcome.exceptionOrNull()?.let { e ->
                if (e is ApiException || e !is IOException || attempt >= MAX_RETRY) throw e
                Thread.sleep(Logic.backoffMillis(attempt))
            }
            attempt++
        }
    }

    fun searchOffers(lang: String, query: Query, page: Int, size: Int = 20) =
        Parse.offerPage(get("/api/v1/concours/search", query.params() + mapOf("lang" to lang, "page" to page, "size" to size)), lang)

    fun offer(id: Long, lang: String) = Parse.offerDetail(get("/api/v1/concours/$id", mapOf("lang" to lang)), lang)

    fun suggest(lang: String, q: String) = Parse.suggestions(get("/api/v1/suggest", mapOf("lang" to lang, "q" to q)))

    fun papers(lang: String, q: String?, page: Int, size: Int = 20) =
        Parse.paperPage(get("/api/v1/modeles", mapOf("lang" to lang, "q" to q, "page" to page, "size" to size)), lang)

    fun paper(id: Int, lang: String) = Parse.paper(get("/api/v1/modeles/$id", mapOf("lang" to lang)).getJSONObject("item"), lang, true)

    fun filters() = Parse.filters(get("/api/v1/filtres", emptyMap()))

    fun salaries(lang: String): JSONObject = get("/api/v1/salaires", mapOf("lang" to lang))
}

/** Pure JSON → model mapping (unit-tested against real API responses in src/test/resources). */
object Parse {
    private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotBlank() }
    private fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { if (isNull(it)) null else optString(it).trim().takeIf { s -> s.isNotBlank() } }

    fun offer(o: JSONObject, lang: String, now: Long = System.currentTimeMillis()) = Offer(
        id = o.getLong("id"), lang = lang, grade = o.str("grade") ?: "",
        employer = o.str("employer"), specialties = o.optJSONArray("specialties").strings(), diplomas = o.optJSONArray("diplomas").strings(),
        region = o.str("region"), city = o.str("city"), posts = o.int("posts"),
        published = o.str("published"), deadline = o.str("deadline"), examDate = o.str("exam_date"),
        salaryMin = o.int("salary_min"), salaryMax = o.int("salary_max"), salaryEstimated = o.optBoolean("salary_estimated"), stage = o.str("stage") ?: "avis",
        url = o.getString("url"), officialNotice = o.str("official_notice"),
        recruitmentType = o.str("recruitment_type"), employerType = o.str("employer_type"), electronic = o.optBoolean("electronic"),
        updated = o.str("updated"),
        noticePdf = o.str("notice_pdf"), applyOnline = o.str("apply_online"), applyAdmin = o.str("apply_admin"),
        employerUrl = o.str("employer_url"), deposit = o.str("deposit"), infos = o.str("infos"),
        documents = docs(o.optJSONArray("documents")), detailLoaded = o.has("documents"), syncedAt = now)

    private fun docs(a: JSONArray?): List<Doc> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .mapNotNull { d -> d.str("url")?.takeIf { it.startsWith("https://") }?.let { Doc(d.str("label"), it) } }.distinctBy { it.url }

    data class Suggestion(val cat: String, val label: String, val value: String)
    fun suggestions(j: JSONObject): List<Suggestion> = j.optJSONArray("items")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { s ->
            val v = s.str("value") ?: return@mapNotNull null
            Suggestion(s.str("cat") ?: "employeur", s.str("label") ?: v, v)
        } } ?: emptyList()

    /** Applies a suggestion to a query the same way the website does (diplome / specialite / city, else keyword). */
    fun apply(s: Suggestion, q: Query): Query = when (s.cat) {
        "diplome" -> q.copy(diplome = s.value, q = null)
        "specialite" -> q.copy(specialite = s.value, q = null)
        "ville" -> q.copy(city = s.value, q = null)
        "region" -> q.copy(region = s.value, q = null)
        else -> q.copy(q = s.value)
    }

    fun offerPage(j: JSONObject, lang: String): Page<Offer> {
        val a = j.getJSONArray("items")
        return Page(j.optInt("page", 1), j.optInt("size", a.length()), j.optInt("total", a.length()), (0 until a.length()).map { offer(a.getJSONObject(it), lang) },
            j.optInt("specialty_matches", -1))
    }

    data class Detail(val offer: Offer, val papers: List<Paper>, val related: List<Offer>)

    fun offerDetail(j: JSONObject, lang: String): Detail {
        val rel = j.optJSONArray("related_exams") ?: JSONArray()
        val ro = j.optJSONArray("related_offers") ?: JSONArray()
        return Detail(offer(j.getJSONObject("item"), lang), (0 until rel.length()).map { paper(rel.getJSONObject(it), lang, false) },
            (0 until ro.length()).map { offer(ro.getJSONObject(it), lang) })
    }

    fun paper(o: JSONObject, lang: String, full: Boolean, now: Long = System.currentTimeMillis()): Paper {
        val docs = o.optJSONArray("documents")
        return Paper(
            id = o.getInt("id"), lang = lang, title = o.str("title") ?: "", employer = o.str("employer"), grade = o.str("grade"),
            specialty = o.str("specialty"), year = o.int("year"), session = o.str("session"), hasCorrection = o.optBoolean("has_correction"),
            pages = o.optInt("pages"), url = o.getString("url"), pdf = o.getString("pdf"),
            description = o.str("description"), source = o.str("source"), images = o.optJSONArray("images").strings(),
            documents = if (docs == null) emptyList() else (0 until docs.length()).map { docs.getJSONObject(it) }.map { Doc(it.str("label"), it.getString("url")) },
            correctionHtml = o.str("correction_html"), correctionKind = o.str("correction_kind"), detailLoaded = full, syncedAt = now)
    }

    fun paperPage(j: JSONObject, lang: String): Page<Paper> {
        val a = j.getJSONArray("items")
        return Page(j.optInt("page", 1), j.optInt("size", a.length()), j.optInt("total", a.length()), (0 until a.length()).map { paper(a.getJSONObject(it), lang, false) })
    }

    fun filters(j: JSONObject): Filters {
        fun list(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            FilterValue(it.getString("value"), it.str("ar") ?: it.getString("value"), it.str("fr") ?: it.getString("value"), it.str("level")) } } ?: emptyList()
        return Filters(list("diplomas"), list("regions"))
    }
}
