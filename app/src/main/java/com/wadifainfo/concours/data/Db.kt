package com.wadifainfo.concours.data

import android.content.Context
import androidx.room.*
import androidx.room.Query   // explicit: wins over data.Query (the search criteria class)
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class Converters {
    @TypeConverter fun fromList(v: List<String>): String = JSONArray(v).toString()
    @TypeConverter fun toList(s: String): List<String> = JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } }
    @TypeConverter fun fromDocs(v: List<Doc>): String = JSONArray(v.map { JSONObject().put("label", it.label).put("url", it.url) }).toString()
    @TypeConverter fun toDocs(s: String): List<Doc> = JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Doc(it.optString("label").ifBlank { null }, it.getString("url")) } }
}

@Dao
interface OfferDao {
    @Upsert suspend fun upsert(items: List<Offer>)
    @Query("SELECT * FROM offers WHERE lang = :lang ORDER BY published DESC, id DESC LIMIT :limit")
    suspend fun latest(lang: String, limit: Int = 200): List<Offer>
    @Query("SELECT * FROM offers WHERE id = :id AND lang = :lang") suspend fun byId(id: Long, lang: String): Offer?
    @Query("DELETE FROM offers WHERE syncedAt < :before") suspend fun prune(before: Long)
}

@Dao
interface PaperDao {
    @Upsert suspend fun upsert(items: List<Paper>)
    @Query("SELECT * FROM papers WHERE lang = :lang ORDER BY id DESC LIMIT :limit") suspend fun all(lang: String, limit: Int = 500): List<Paper>
    @Query("SELECT * FROM papers WHERE id = :id AND lang = :lang") suspend fun byId(id: Int, lang: String): Paper?
}

@Dao
interface QuizDao {
    @Upsert suspend fun upsertBank(b: Bank)
    @Upsert suspend fun upsertQuestions(q: List<Question>)
    @Query("DELETE FROM questions WHERE bankId = :bankId") suspend fun clearBank(bankId: String)
    @Query("DELETE FROM banks WHERE id = :id") suspend fun deleteBank(id: String)
    @Query("SELECT * FROM banks WHERE lang = :lang ORDER BY kind DESC, titleFr") fun banks(lang: String): Flow<List<Bank>>
    @Query("SELECT * FROM banks WHERE id = :id") suspend fun bank(id: String): Bank?
    @Query("SELECT * FROM questions WHERE bankId = :bankId") suspend fun questions(bankId: String): List<Question>
    @Query("SELECT * FROM questions WHERE id IN (:ids)") suspend fun questionsByIds(ids: List<String>): List<Question>
    @Insert suspend fun insertAttempt(a: Attempt): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAnswers(a: List<Answer>)
    @Query("SELECT * FROM attempts ORDER BY finishedAt DESC LIMIT 50") fun attempts(): Flow<List<Attempt>>
    /** Questions answered wrong at least once and never answered right afterwards. */
    @Query("""SELECT q.* FROM questions q WHERE q.bankId = :bankId AND q.id IN (
        SELECT a.questionId FROM answers a JOIN attempts t ON t.id = a.attemptId GROUP BY a.questionId
        HAVING SUM(CASE WHEN a.correct THEN 0 ELSE 1 END) > 0 AND MAX(CASE WHEN a.correct THEN t.finishedAt ELSE 0 END) < MAX(CASE WHEN a.correct THEN 0 ELSE t.finishedAt END))""")
    suspend fun toReview(bankId: String): List<Question>
}

@Dao
interface SavedSearchDao {
    @Insert suspend fun add(s: SavedSearch): Long
    @Update suspend fun update(s: SavedSearch)
    @Delete suspend fun remove(s: SavedSearch)
    @Query("SELECT * FROM saved_searches ORDER BY createdAt DESC") fun all(): Flow<List<SavedSearch>>
    @Query("SELECT * FROM saved_searches WHERE notify = 1") suspend fun notifying(): List<SavedSearch>
}

@Dao
interface RecentDao {
    @Upsert suspend fun add(r: Recent)
    @Query("SELECT * FROM recent ORDER BY viewedAt DESC LIMIT 20") fun all(): Flow<List<Recent>>
    @Query("DELETE FROM recent WHERE offerId NOT IN (SELECT offerId FROM recent ORDER BY viewedAt DESC LIMIT 50)") suspend fun trim()
    @Query("DELETE FROM recent") suspend fun clear()
}

@Dao
interface NotifiedDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(n: List<Notified>)
    @Query("SELECT key FROM notified WHERE key IN (:keys)") suspend fun existing(keys: List<String>): List<String>
    @Query("DELETE FROM notified WHERE at < :before") suspend fun prune(before: Long)
}

@Dao
interface DownloadDao {
    @Upsert suspend fun add(d: Download)
    @Query("SELECT * FROM downloads WHERE url = :url") suspend fun byUrl(url: String): Download?
    @Query("SELECT * FROM downloads ORDER BY at DESC") fun all(): Flow<List<Download>>
    @Query("DELETE FROM downloads WHERE url = :url") suspend fun remove(url: String)
    @Query("SELECT * FROM downloads ORDER BY at DESC") suspend fun newestFirst(): List<Download>
}

@Dao
interface FavoriteDao {
    @Upsert suspend fun add(f: Favorite)
    @Query("DELETE FROM favorites WHERE type = :type AND refId = :refId") suspend fun remove(type: String, refId: String)
    @Query("SELECT * FROM favorites ORDER BY createdAt DESC") fun all(): Flow<List<Favorite>>
    @Query("SELECT COUNT(*) FROM favorites WHERE type = :type AND refId = :refId") fun isFav(type: String, refId: String): Flow<Int>
    @Query("SELECT * FROM favorites WHERE type = 'offer' AND deadline IS NOT NULL") suspend fun offersWithDeadline(): List<Favorite>
    @Query("SELECT * FROM favorites WHERE type = :type AND refId = :refId") suspend fun get(type: String, refId: String): Favorite?
    @Query("SELECT COUNT(*) FROM favorites WHERE type = 'offer'") suspend fun offerCount(): Int
}

@Database(entities = [Offer::class, Paper::class, Question::class, Bank::class, Attempt::class, Answer::class, Favorite::class,
    SavedSearch::class, Recent::class, Notified::class, Download::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun offers(): OfferDao
    abstract fun papers(): PaperDao
    abstract fun quiz(): QuizDao
    abstract fun favorites(): FavoriteDao
    abstract fun saved(): SavedSearchDao
    abstract fun recent(): RecentDao
    abstract fun notified(): NotifiedDao
    abstract fun downloads(): DownloadDao

    companion object {
        @Volatile private var inst: AppDb? = null

        /** 2.2: offers.salaryEstimated. Additive, so favourites, attempts and downloads survive the update. */
        val M1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offers ADD COLUMN salaryEstimated INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "concours.db").addMigrations(M1_2).build().also { inst = it }
        }
    }
}
