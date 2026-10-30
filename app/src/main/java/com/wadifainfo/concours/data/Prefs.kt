package com.wadifainfo.concours.data

import android.content.Context

/** Profile + consent. Nothing here leaves the phone. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)
    var diploma: String? get() = p.getString("diploma", null); set(v) { p.edit().putString("diploma", v).apply() }
    var region: String? get() = p.getString("region", null); set(v) { p.edit().putString("region", v).apply() }
    var specialty: String? get() = p.getString("specialty", null); set(v) { p.edit().putString("specialty", v).apply() }
    /** Saved-search alerts: opt-in, off by default. (Deadline reminders have their own switch below.) */
    var alerts: Boolean get() = p.getBoolean("alerts", false); set(v) { p.edit().putBoolean("alerts", v).apply() }
    /** Background check period in hours: 12 or 24. */
    var alertHours: Int get() = p.getInt("alertHours", 12); set(v) { p.edit().putInt("alertHours", v).apply() }
    var reminders: Boolean get() = p.getBoolean("reminders", true); set(v) { p.edit().putBoolean("reminders", v).apply() }
    /** At least one followed offer: deadline reminders need the background check even when saved-search alerts are off. */
    var hasFollowed: Boolean get() = p.getBoolean("hasFollowed", false); set(v) { p.edit().putBoolean("hasFollowed", v).apply() }
    /** Saved-search ids whose first check already ran (later checks may notify). */
    fun baselined(id: Long) = p.getBoolean("base:$id", false)
    fun setBaselined(id: Long) = p.edit().putBoolean("base:$id", true).apply()

    /** Open-offer count last seen online (Home shows it offline, labelled as the last known value). */
    fun lastOpenCount(lang: String): Int? = p.getInt("open:$lang", -1).takeIf { it >= 0 }
    fun setLastOpenCount(lang: String, n: Int) = p.edit().putInt("open:$lang", n).apply()

    /** App language as last shown ("ar"/"fr"); null before the first launch. */
    var uiLang: String? get() = p.getString("uiLang", null); set(v) { p.edit().putString("uiLang", v).apply() }

    fun lastSync(lang: String) = p.getLong("sync:$lang", 0)
    fun setLastSync(lang: String, t: Long) = p.edit().putLong("sync:$lang", t).apply()

    var recentSearches: List<String>
        get() = p.getString("recentQ", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) { p.edit().putString("recentQ", v.take(8).joinToString("\n")).apply() }
    fun addRecentSearch(q: String) { val t = q.trim(); if (t.length >= 2) recentSearches = listOf(t) + recentSearches.filter { it != t } }
}
