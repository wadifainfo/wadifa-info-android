package com.wadifainfo.concours.data

import org.json.JSONObject
import java.security.MessageDigest

/** Scoring and the import format for question banks (see docs/IMPORT.md). Pure Kotlin, unit-tested. */
object Quiz {
    data class Result(val score: Int, val total: Int, val wrong: List<String>) {
        val percent: Int get() = if (total == 0) 0 else Math.round(score * 100f / total)
    }

    /** answers: questionId → chosen option index (-1 or missing = not answered, counted wrong). */
    fun score(questions: List<Question>, answers: Map<String, Int>): Result {
        val wrong = questions.filter { answers[it.id] != it.correctIndex }.map { it.id }
        return Result(questions.size - wrong.size, questions.size, wrong)
    }

    /** Stable id from the content, so re-importing the same question never duplicates it. */
    fun contentId(bankId: String, stem: String, options: List<String>): String {
        val norm = (listOf(stem) + options).joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        val h = MessageDigest.getInstance("SHA-256").digest("$bankId|$norm".toByteArray())
        return h.take(12).joinToString("") { "%02x".format(it) }
    }

    class ImportError(msg: String) : Exception(msg)
    data class Imported(val bank: Bank, val questions: List<Question>, val skippedDuplicates: Int, val rejected: List<String>)

    private val KINDS = setOf("official", "practice")
    private val EXPL = setOf("official", "editorial", "ai")

    /**
     * Validates a bank JSON (schema in docs/IMPORT.md). Rejects any question whose provenance is missing or inconsistent:
     * "official" needs a sourceUrl and an official explanation kind is only allowed for official questions.
     */
    fun parseBank(json: String): Imported {
        val j = try { JSONObject(json) } catch (e: Exception) { throw ImportError("invalid JSON: ${e.message}") }
        val id = j.optString("id").ifBlank { throw ImportError("bank.id missing") }
        val kind = j.optString("kind").ifBlank { throw ImportError("bank.kind missing") }
        if (kind !in KINDS) throw ImportError("bank.kind must be official|practice")
        val source = j.optString("source").ifBlank { throw ImportError("bank.source missing") }
        val lang = j.optString("lang", "fr")
        val arr = j.optJSONArray("questions") ?: throw ImportError("questions missing")
        val seen = HashSet<String>(); var dups = 0; val rejected = mutableListOf<String>(); val out = mutableListOf<Question>()
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: run { rejected += "#$i not an object"; null } ?: continue
            val stem = q.optString("stem").trim()
            val opts = q.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } } ?: emptyList()
            val ci = q.optInt("correct", -1)
            val qKind = q.optString("kind", kind)
            val ek = q.optString("explanationKind").ifBlank { null }
            val su = q.optString("sourceUrl").ifBlank { null }
            val problem = when {
                stem.isEmpty() -> "empty stem"
                opts.size < 2 || opts.any { it.isEmpty() } -> "needs 2+ non-empty options"
                opts.toSet().size != opts.size -> "duplicate options"
                ci !in opts.indices -> "correct index out of range"
                qKind !in KINDS -> "kind must be official|practice"
                qKind == "official" && su == null -> "official question without sourceUrl"
                ek != null && ek !in EXPL -> "explanationKind must be official|editorial|ai"
                ek == "official" && qKind != "official" -> "official explanation on a practice question"
                q.optString("explanation").isNotBlank() && ek == null -> "explanation without explanationKind"
                else -> null
            }
            if (problem != null) { rejected += "#$i $problem"; continue }
            val qid = contentId(id, stem, opts)
            if (!seen.add(qid)) { dups++; continue }
            out += Question(qid, id, q.optString("subject", j.optString("subject", "")), stem, opts, ci,
                q.optString("explanation").ifBlank { null }, ek, qKind, q.optString("source", source), su, q.optString("lang", lang))
        }
        if (out.isEmpty()) throw ImportError("no valid question (${rejected.size} rejected)")
        val bank = Bank(id, j.optString("titleAr", j.optString("titleFr", id)), j.optString("titleFr", id), kind, source, j.optInt("version", 1), out.size, lang)
        return Imported(bank, out, dups, rejected)
    }
}
