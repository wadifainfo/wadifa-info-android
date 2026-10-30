package com.wadifainfo.concours.data

import androidx.room.Entity
import androidx.room.Index

/** A recruitment offer as served by GET /api/v1/concours/search and /api/v1/concours/{id}. Keyed by the site's stable id. */
@Entity(tableName = "offers", primaryKeys = ["id", "lang"])
data class Offer(
    val id: Long,
    val lang: String,
    val grade: String,
    val employer: String?,
    val specialties: List<String>,
    val diplomas: List<String>,
    val region: String?,
    val city: String?,
    val posts: Int?,
    val published: String?,
    val deadline: String?,
    val examDate: String?,
    val salaryMin: Int?,
    val salaryMax: Int?,
    /** true = estimated band (public bodies); false = the grid salary given by the notice. */
    val salaryEstimated: Boolean = false,
    val stage: String,
    val url: String,
    val officialNotice: String?,
    val recruitmentType: String? = null,
    /** "state" (ministries), "local" (collectivités), "public_body" (établissements publics). */
    val employerType: String? = null,
    /** Application filed online on a portal (vs. paper/post). */
    val electronic: Boolean = false,
    /** Last time the official files changed on the portal (site freshness signal). */
    val updated: String? = null,
    // Detail-only fields (null in list rows; detailLoaded tells them apart).
    val noticePdf: String? = null,
    val applyOnline: String? = null,
    val applyAdmin: String? = null,
    val employerUrl: String? = null,
    val deposit: String? = null,
    val infos: String? = null,
    val documents: List<Doc> = emptyList(),
    val detailLoaded: Boolean = false,
    val syncedAt: Long = 0,
)

/** Search criteria; also the shape of a saved search. Values are the API filter values. */
data class Query(
    val q: String? = null,
    val diplome: String? = null,
    val specialite: String? = null,
    val region: String? = null,
    val city: String? = null,
    /** 1 regular posts only, 2 contracts only, null both. */
    val contract: Int? = null,
    val etype: String? = null,
    val electronic: Boolean = false,
    /** API sort: 4 newest, 5 closing soon, 3 most posts, 2 salary. */
    val sort: Int = 4,
    val includeClosed: Boolean = false,
) : java.io.Serializable {
    val isEmpty get() = q.isNullOrBlank() && diplome == null && specialite == null && region == null && city == null &&
        contract == null && etype == null && !electronic
    fun params(): Map<String, Any?> = mapOf("q" to q?.trim(), "diplome" to diplome, "specialite" to specialite, "region" to region,
        "city" to city, "contract" to contract, "etype" to etype, "electronic" to if (electronic) 1 else null, "sort" to sort,
        "all" to if (includeClosed) 1 else null)
}

@Entity(tableName = "saved_searches")
data class SavedSearch(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val q: String?, val diplome: String?, val specialite: String?, val region: String?, val city: String?,
    val contract: Int?, val etype: String?, val electronic: Boolean,
    /** Opt-in per search: the background check notifies about new matching offers. */
    val notify: Boolean,
    val createdAt: Long,
) {
    fun query() = Query(q, diplome, specialite, region, city, contract, etype, electronic)
    companion object {
        fun of(label: String, q: Query, notify: Boolean, now: Long = System.currentTimeMillis()) =
            SavedSearch(0, label, q.q, q.diplome, q.specialite, q.region, q.city, q.contract, q.etype, q.electronic, notify, now)
    }
}

@Entity(tableName = "recent")
data class Recent(@androidx.room.PrimaryKey val offerId: Long, val title: String, val employer: String?, val viewedAt: Long)

/** One row per notification ever shown (key = "new:<offerId>" or "due:<offerId>:<days>"), so nothing is notified twice. */
@Entity(tableName = "notified")
data class Notified(@androidx.room.PrimaryKey val key: String, val at: Long)

/** A downloaded document, kept for offline reading. */
@Entity(tableName = "downloads")
data class Download(@androidx.room.PrimaryKey val url: String, val file: String, val label: String, val bytes: Long, val at: Long)

/** A real past exam paper from concoursModeles (GET /api/v1/modeles). kind is always "past_exam". */
@Entity(tableName = "papers", primaryKeys = ["id", "lang"])
data class Paper(
    val id: Int,
    val lang: String,
    val title: String,
    val employer: String?,
    val grade: String?,
    val specialty: String?,
    val year: Int?,
    val session: String?,
    val hasCorrection: Boolean,
    val pages: Int,
    val url: String,
    val pdf: String,
    val description: String? = null,
    val source: String? = null,
    val images: List<String> = emptyList(),
    val documents: List<Doc> = emptyList(),
    val correctionHtml: String? = null,
    /** "editorial" = written by Wadifa Info; never presented as an official answer key. */
    val correctionKind: String? = null,
    val detailLoaded: Boolean = false,
    val syncedAt: Long = 0,
)

data class Doc(val label: String?, val url: String)

data class FilterValue(val value: String, val ar: String, val fr: String, val level: String? = null)
data class Filters(val diplomas: List<FilterValue>, val regions: List<FilterValue>)

/** specialtyMatches: with a specialty filter, the first N rows of the full result really carry that specialty (-1 = n/a). */
data class Page<T>(val page: Int, val size: Int, val total: Int, val items: List<T>, val specialtyMatches: Int = -1)

/**
 * Practice question. Provenance is mandatory:
 *  kind = "official"  → taken from a real past paper with its official answer key (sourceUrl required)
 *  kind = "practice"  → exercise written or generated by Wadifa Info (never shown as a historical question)
 * explanationKind = "official" | "editorial" | "ai" — shown to the user next to every explanation.
 */
@Entity(tableName = "questions", indices = [Index("bankId")])
data class Question(
    @androidx.room.PrimaryKey val id: String,
    val bankId: String,
    val subject: String,
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String?,
    val explanationKind: String?,
    val kind: String,
    val source: String,
    val sourceUrl: String?,
    val lang: String,
)

@Entity(tableName = "banks")
data class Bank(
    @androidx.room.PrimaryKey val id: String,
    val titleAr: String,
    val titleFr: String,
    val kind: String,
    val source: String,
    val version: Int,
    val questionCount: Int,
    val lang: String,
)

@Entity(tableName = "attempts")
data class Attempt(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: String,
    val mode: String,            // "practice" | "exam"
    val startedAt: Long,
    val finishedAt: Long,
    val score: Int,
    val total: Int,
)

@Entity(tableName = "answers", primaryKeys = ["attemptId", "questionId"])
data class Answer(val attemptId: Long, val questionId: String, val chosen: Int, val correct: Boolean)

@Entity(tableName = "favorites", primaryKeys = ["type", "refId"])
data class Favorite(val type: String, val refId: String, val title: String, val createdAt: Long,
    /** Offers only: deadline copied at bookmark time so reminders work offline. */
    val deadline: String? = null, val employer: String? = null)
