package com.wadifainfo.concours

import com.wadifainfo.concours.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixtures in src/test/resources are real responses saved from www.wadifa-info.com/api/v1 on 2026-10-13. */
class DataTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test fun searchPageParses() {
        val p = Parse.offerPage(fixture("search_fr.json"), "fr")
        assertEquals(1, p.page); assertEquals(3, p.items.size); assertTrue(p.total >= p.items.size)
        p.items.forEach { assertTrue(it.id > 0); assertTrue(it.url.startsWith("https://www.wadifa-info.com/")); assertTrue(it.grade.isNotBlank()) }
    }

    @Test fun offerDetailHasRelatedPapers() {
        val (o, rel, _) = Parse.offerDetail(fixture("offer_ar.json"), "ar")
        assertEquals(54556L, o.id); assertEquals("ar", o.lang)
        assertTrue(rel.isNotEmpty()); rel.forEach { assertTrue(it.pdf.endsWith("/pdf")); assertFalse(it.detailLoaded) }
    }

    @Test fun paperDetailKeepsProvenance() {
        val p = Parse.paper(fixture("modele_fr.json").getJSONObject("item"), "fr", true)
        assertEquals(1593, p.id); assertTrue(p.detailLoaded); assertTrue(p.images.isNotEmpty())
        assertTrue(p.hasCorrection); assertNotNull(p.correctionHtml); assertEquals("editorial", p.correctionKind)
    }

    @Test fun papersPageAndFilters() {
        assertEquals(3, Parse.paperPage(fixture("modeles_fr.json"), "fr").items.size)
        val f = Parse.filters(fixture("filtres.json"))
        assertTrue(f.diplomas.size > 5); assertTrue(f.regions.size >= 12)
        f.diplomas.forEach { assertTrue(it.value.isNotBlank() && it.fr.isNotBlank()) }
    }

    private fun q(id: String, correct: Int) = Question(id, "b", "s", "stem $id", listOf("a", "b", "c"), correct, null, null, "practice", "src", null, "fr")

    @Test fun scoring() {
        val qs = listOf(q("1", 0), q("2", 1), q("3", 2))
        val r = Quiz.score(qs, mapOf("1" to 0, "2" to 2))   // 3 unanswered = wrong
        assertEquals(1, r.score); assertEquals(3, r.total); assertEquals(listOf("2", "3"), r.wrong); assertEquals(33, r.percent)
        assertEquals(0, Quiz.score(emptyList(), emptyMap()).percent)
    }

    @Test fun importValidatesProvenanceAndDedupes() {
        val json = """{"id":"t","kind":"practice","source":"Wadifa Info","titleFr":"Test","questions":[
            {"stem":"2+2 ?","options":["3","4"],"correct":1,"explanation":"calcul","explanationKind":"editorial"},
            {"stem":" 2+2  ?","options":["3","4"],"correct":1},
            {"stem":"x","options":["a","b"],"correct":0,"explanationKind":"official"},
            {"stem":"y","options":["a","b"],"correct":0,"kind":"official"},
            {"stem":"z","options":["a","a"],"correct":0},
            {"stem":"w","options":["a","b"],"correct":5},
            {"stem":"v","options":["a","b"],"correct":0,"explanation":"no kind"}]}"""
        val imp = Quiz.parseBank(json)
        assertEquals(1, imp.questions.size); assertEquals(1, imp.skippedDuplicates); assertEquals(5, imp.rejected.size)
        assertTrue(imp.rejected.any { "official explanation on a practice" in it })
        assertTrue(imp.rejected.any { "without sourceUrl" in it })
        assertEquals(Quiz.parseBank(json).questions[0].id, imp.questions[0].id)   // stable ids → re-import never duplicates
    }

    @Test(expected = Quiz.ImportError::class) fun importRejectsBadBank() { Quiz.parseBank("""{"id":"t","kind":"official_maybe","source":"x","questions":[]}""") }

    /** Every bundled site bank (tools/site_qcm.py) must import whole: no rejected question, both languages, practice only. */
    @Test fun bundledSiteBanksImportCleanly() {
        val dir = java.io.File("src/main/assets/qcm")
        val files = dir.listFiles { f -> f.name.startsWith("site-") && f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertEquals(12, files.size)
        files.forEach { f ->
            val imp = Quiz.parseBank(f.readText())
            assertTrue("${f.name}: ${imp.rejected}", imp.rejected.isEmpty())
            assertEquals(0, imp.skippedDuplicates)
            imp.questions.forEach { assertEquals("practice", it.kind); assertTrue(it.sourceUrl!!.startsWith("https://www.wadifa-info.com/")) }
        }
    }

    @Test fun paginationMergeDedupes() {
        assertEquals(listOf(1, 2, 3, 4), Paging.merge(listOf(1, 2, 3), listOf(3, 4, 4)) { it })
    }

    @Test fun offlineFilterMatchesLabelsInBothLanguages() {
        val o = Parse.offerDetail(fixture("offer_ar.json"), "ar").offer
        val f = Filters(listOf(FilterValue("x", o.diplomas.first(), "Licence")), emptyList())
        val all = Query(includeClosed = true)
        assertEquals(1, Paging.filterLocal(listOf(o), all.copy(diplome = "x"), f).size)
        assertEquals(0, Paging.filterLocal(listOf(o), all.copy(diplome = "y"), f).size)
        assertTrue(Paging.matches("أ", "ا"))   // short tokens ignored
        assertTrue(Paging.matches("INFIRMIER généraliste", "Infirmier polyvalent generaliste"))
    }

    @Test fun newApiFieldsAndDocuments() {
        val d = Parse.offerDetail(fixture("offer_docs_fr.json"), "fr")
        val o = d.offer
        assertTrue(o.detailLoaded); assertEquals("public_body", o.employerType); assertTrue(o.electronic)
        assertEquals("Recrutement régulier", o.recruitmentType)
        assertTrue(o.documents.size >= 2); o.documents.forEach { assertTrue(it.url.startsWith("https://")) }
        assertEquals(o.documents.size, o.documents.map { it.url }.toSet().size)
        assertNull(o.infos)   // JSON null must stay null, never the string "null"
        assertTrue(d.related.none { it.id == o.id })
    }

    @Test fun listRowsAreNotDetail() {
        Parse.offerPage(fixture("search_fr.json"), "fr").items.forEach { assertFalse(it.detailLoaded); assertTrue(it.documents.isEmpty()) }
    }

    @Test fun malformedOfferFieldsDegradeGracefully() {
        val o = Parse.offer(JSONObject("""{"id":5,"url":"https://www.wadifa-info.com/5/x","grade":null,"employer":"  ","posts":null,
            "specialties":["", "a", null],"documents":[{"label":"x","url":"javascript:alert(1)"},{"url":"https://e.ma/a.pdf"},{"url":"https://e.ma/a.pdf"}],"deadline":"pas une date"}"""), "fr")
        assertEquals("", o.grade); assertNull(o.employer); assertNull(o.posts); assertEquals(listOf("a"), o.specialties)
        assertEquals(1, o.documents.size); assertNull(o.documents[0].label)
        assertEquals(Logic.Status.UNKNOWN, Logic.status(o.deadline))
    }

    @Test fun suggestionsMapToTheRightFilter() {
        val s = Parse.suggestions(fixture("suggest_fr.json"))
        assertTrue(s.isNotEmpty()); assertTrue(s.all { it.cat == "specialite" })
        val q = Parse.apply(s[0], Query(q = "infirm"))
        assertEquals(s[0].value, q.specialite); assertNull(q.q)
        assertEquals("Rabat", Parse.apply(Parse.Suggestion("ville", "Rabat", "Rabat"), Query()).city)
        assertEquals("ONDA", Parse.apply(Parse.Suggestion("employeur", "ONDA", "ONDA"), Query()).q)
    }

    @Test fun queryParamsOnlySendWhatIsSet() {
        val p = Query(q = " infirmier ", contract = 2, electronic = true, sort = 5).params().filterValues { it != null }
        assertEquals(mapOf("q" to "infirmier", "contract" to 2, "electronic" to 1, "sort" to 5), p)
        assertTrue(Query().isEmpty); assertFalse(Query(etype = "local").isEmpty)
    }

    @Test fun offlineSortAndClosedFilter() {
        fun o(id: Long, pub: String, dl: String?, posts: Int?) = Offer(id, "fr", "g$id", null, emptyList(), emptyList(), null, null, posts,
            pub, dl, null, null, null, stage = "avis", url = "u", officialNotice = null)
        val today = java.time.LocalDate.of(2026, 10, 14)
        val list = listOf(o(1, "2026-10-01", "2026-10-20", 3), o(2, "2026-10-10", "2026-10-15", 9), o(3, "2026-10-12", "2026-10-01", 1), o(4, "2026-10-13", null, null))
        assertEquals(listOf(1L, 2L, 4L), Paging.filterLocal(list, Query(), null, today).map { it.id }.sorted())
        assertEquals(listOf(4L, 3L, 2L, 1L), Paging.sortLocal(list, 4).map { it.id })
        assertEquals(listOf(3L, 2L, 1L, 4L), Paging.sortLocal(list, 5).map { it.id })   // unknown deadline last
        assertEquals(2L, Paging.sortLocal(list, 3).first().id)
    }

    @Test fun deadlineStatus() {
        val t = java.time.LocalDate.of(2026, 10, 14)
        assertEquals(Logic.Status.CLOSED, Logic.status("2026-10-13", t))
        assertEquals(Logic.Status.LAST_DAY, Logic.status("2026-10-14", t))
        assertEquals(Logic.Status.CLOSING, Logic.status("2026-10-17", t))
        assertEquals(Logic.Status.OPEN, Logic.status("2026-10-18T00:00:00", t))
        assertEquals(Logic.Status.UNKNOWN, Logic.status(null, t))
        assertEquals(Logic.Status.UNKNOWN, Logic.status("", t))
    }

    @Test fun remindersFireOnlyOnReminderDays() {
        val t = java.time.LocalDate.of(2026, 10, 14)
        assertEquals("due:9:7", Logic.reminderKey(9, "2026-10-21", t))
        assertEquals("due:9:0", Logic.reminderKey(9, "2026-10-14", t))
        assertEquals("due:9:7", Logic.reminderKey(9, "2026-10-19", t))   // 5 days: the missed 7-day reminder catches up (same key = once)
        assertEquals("due:9:3", Logic.reminderKey(9, "2026-10-16", t))   // 2 days -> 3-day threshold
        assertNull(Logic.reminderKey(9, "2026-10-30", t))                // 16 days: nothing yet
        assertNull(Logic.reminderKey(9, "2026-10-13", t))   // closed
        assertNull(Logic.reminderKey(9, null, t))
    }

    @Test fun alertDedupeBaselineAndExpiry() {
        val t = java.time.LocalDate.of(2026, 10, 14)
        fun o(id: Long, pub: String, dl: String?) = Offer(id, "fr", "g", null, emptyList(), emptyList(), null, null, null, pub, dl, null, null, null, stage = "avis", url = "u", officialNotice = null)
        val items = listOf(o(1, "2026-10-14", "2026-10-30"), o(2, "2026-10-13", "2026-10-30"), o(3, "2026-10-01", "2026-10-30"),
            o(4, "2026-10-14", "2026-10-10"), o(1, "2026-10-14", "2026-10-30"))
        assertTrue(Logic.newForAlert(items, emptySet(), baseline = true, today = t).isEmpty())          // first run never floods
        assertEquals(listOf(1L, 2L), Logic.newForAlert(items, emptySet(), baseline = false, today = t).map { it.id })   // old (3) + closed (4) skipped, dup 1 once
        assertEquals(listOf(2L), Logic.newForAlert(items, setOf("new:1"), baseline = false, today = t).map { it.id })  // never twice
    }

    @Test fun deepLinks() {
        assertEquals(126222L, offerIdFromPath("www.wadifa-info.com", listOf("126222", "slug")))
        assertEquals(126222L, offerIdFromPath("wadifa-info.com", listOf("fr", "126222", "slug")))
        assertNull(offerIdFromPath("www.wadifa-info.com", listOf("fr", "confidentialite")))
        assertNull(offerIdFromPath("evil-wadifa-info.com", listOf("126222")))
        assertNull(offerIdFromPath(null, null))
    }

    @Test fun pdfMagic() {
        assertTrue(com.wadifainfo.concours.data.Docs.isPdf("%PDF-1.7".toByteArray()))
        assertFalse(com.wadifainfo.concours.data.Docs.isPdf("<html>".toByteArray()))
        assertFalse(com.wadifainfo.concours.data.Docs.isPdf(ByteArray(2)))
        assertEquals(com.wadifainfo.concours.data.Docs.name("https://a/b"), com.wadifainfo.concours.data.Docs.name("https://a/b"))
    }

    @Test fun arabicIndicDigitsBecomeLatin() {
        assertEquals("الكل (207) · 5 مناصب", com.wadifainfo.concours.ui.latinDigits("الكل (٢٠٧) · ٥ مناصب"))
        assertEquals("abc 12", com.wadifainfo.concours.ui.latinDigits("abc 12"))
    }

    @Test fun backoffHonoursRetryAfterAndGrows() {
        assertEquals(3000L, Logic.backoffMillis(0, "3", jitter = 0))
        assertEquals(8000L, Logic.backoffMillis(0, "120", jitter = 0))       // capped
        assertEquals(700L, Logic.backoffMillis(0, null, jitter = 0))
        assertEquals(1400L, Logic.backoffMillis(1, "soon", jitter = 0))     // unparsable header -> exponential
    }

    @Test fun moroccoToday() {
        assertEquals("Africa/Casablanca", Logic.MOROCCO.id)
        assertEquals(java.time.LocalDate.now(java.time.ZoneId.of("Africa/Casablanca")), Logic.today())
    }

    @Test fun documentSignatures() {
        assertNull(com.wadifainfo.concours.data.Docs.classify("%PDF-1.4".toByteArray()))
        assertEquals(com.wadifainfo.concours.data.Docs.Reason.OTHER_FILE, com.wadifainfo.concours.data.Docs.classify(byteArrayOf(0x50, 0x4B, 3, 4)))
        assertEquals(com.wadifainfo.concours.data.Docs.Reason.OTHER_FILE,
            com.wadifainfo.concours.data.Docs.classify(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())))
        assertEquals(com.wadifainfo.concours.data.Docs.Reason.NOT_PDF, com.wadifainfo.concours.data.Docs.classify("<!DOCTYPE".toByteArray()))
    }

    @Test fun salaryFromApiAndLabel() {
        val est = Parse.offer(JSONObject("""{"id":1,"url":"https://www.wadifa-info.com/1/x","salary_min":8100,"salary_max":10900,"salary_estimated":true}"""), "fr")
        val off = Parse.offer(JSONObject("""{"id":2,"url":"https://www.wadifa-info.com/2/x","salary_min":16658,"salary_max":16658,"salary_estimated":false}"""), "fr")
        val none = Parse.offer(JSONObject("""{"id":3,"url":"https://www.wadifa-info.com/3/x"}"""), "fr")
        assertTrue(est.salaryEstimated); assertFalse(off.salaryEstimated); assertFalse(none.salaryEstimated)
        assertEquals("⁦≈ 8 100 – 10 900 DH⁩", com.wadifainfo.concours.ui.salaryText(est))
        assertEquals("⁦16 658 DH⁩", com.wadifainfo.concours.ui.salaryText(off))
        assertNull(com.wadifainfo.concours.ui.salaryText(none))
    }

    @Test fun contractKindFromCachedLabels() {
        assertEquals(1, Logic.contractKind("Recrutement régulier")); assertEquals(1, Logic.contractKind("توظيف نظامي"))
        assertEquals(2, Logic.contractKind("Recrutement sous contrat")); assertEquals(2, Logic.contractKind("توظيف بموجب عقد"))
        assertNull(Logic.contractKind(null)); assertNull(Logic.contractKind("Autre"))
    }

    @Test fun offlineFilterHonoursContractAndLeavesUnknownOut() {
        fun o(id: Long, type: String?) = Offer(id, "fr", "g$id", null, emptyList(), emptyList(), null, null, null,
            "2026-10-10", "2026-12-01", null, null, null, stage = "avis", url = "u", officialNotice = null, recruitmentType = type)
        val today = java.time.LocalDate.of(2026, 10, 14)
        val list = listOf(o(1, "Recrutement régulier"), o(2, "Recrutement sous contrat"), o(3, null))
        assertEquals(listOf(1L), Paging.filterLocal(list, Query(contract = 1), null, today).map { it.id })
        assertEquals(listOf(2L), Paging.filterLocal(list, Query(contract = 2), null, today).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), Paging.filterLocal(list, Query(), null, today).map { it.id })
    }

    @Test fun reminderThresholdsCatchUpAndStopWhenClosed() {
        val today = java.time.LocalDate.of(2026, 10, 14)
        assertNull(Logic.reminderKey(9, "2026-10-30", today))                 // more than 7 days: nothing yet
        assertEquals("due:9:7", Logic.reminderKey(9, "2026-10-21", today))
        assertEquals("due:9:7", Logic.reminderKey(9, "2026-10-20", today))    // 6 days left, the 7-day reminder was missed
        assertEquals("due:9:1", Logic.reminderKey(9, "2026-10-15", today))
        assertEquals("due:9:0", Logic.reminderKey(9, "2026-10-14", today))
        assertNull(Logic.reminderKey(9, "2026-10-13", today))                 // closed
        assertNull(Logic.reminderKey(9, null, today))
    }
}
