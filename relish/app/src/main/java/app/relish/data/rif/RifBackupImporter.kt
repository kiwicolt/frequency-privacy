package app.relish.data.rif

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Xml
import app.relish.data.local.FilterDao
import app.relish.data.local.FilterEntity
import app.relish.data.local.FilterType
import app.relish.data.local.ReadPostDao
import app.relish.data.local.ReadPostEntity
import app.relish.data.local.SubscriptionDao
import app.relish.data.local.SubscriptionEntity
import app.relish.data.model.CommentSort
import app.relish.data.prefs.FontSize
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.prefs.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class RifImportSummary(
    val subscriptions: Int,
    val nsfwSubscriptions: Int,
    val readMarks: Int,
    val filters: Int,
    val settings: Int,
    val nsfwShortcuts: Int = 0,
) {
    override fun toString() = buildList {
        add("$subscriptions subscriptions" + if (nsfwSubscriptions > 0) " ($nsfwSubscriptions NSFW)" else "")
        if (nsfwShortcuts > 0) add("$nsfwShortcuts NSFW shortcuts")
        if (readMarks > 0) add("$readMarks read marks")
        if (filters > 0) add("$filters filters")
        if (settings > 0) add("$settings settings")
    }.joinToString(", ", prefix = "Imported from Reddit is Fun: ")
}

/**
 * Imports a "Reddit is Fun" settings backup (Settings › Backup › Export settings [AND HISTORY]).
 *
 * The zip holds the app's SQLite databases and preferences XML:
 *  - reddits.db › reddits: one row per known subreddit; `frontpage = 1` marks subscriptions,
 *    plus `nsfw` and `favorite` flags (the rest are merely visited subreddits)
 *  - threadfilters.db › thread_filters: keyword/subreddit/domain filters
 *  - browserhistory.db › uri_history: `thread_id` of every opened post → Relish read marks
 *  - settings.xml: SharedPreferences, mapped onto the equivalent Relish settings
 *
 * Only those known entries are extracted, to fixed paths in the cache dir, and opened read-only.
 */
@Singleton
class RifBackupImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionDao: SubscriptionDao,
    private val readDao: ReadPostDao,
    private val filterDao: FilterDao,
    private val prefs: PreferencesDataStore,
    private val shortcutDao: app.relish.data.local.NsfwShortcutDao,
) {
    suspend fun import(uri: Uri): RifImportSummary = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "rif_import").apply { deleteRecursively(); mkdirs() }
        try {
            extract(uri, dir)
            val reddits = File(dir, "reddits.db")
            if (!reddits.exists()) throw IllegalArgumentException("That doesn't look like a Reddit is Fun backup (no reddits.db)")
            val (subs, nsfw) = importSubscriptions(reddits)
            val shortcuts = importNsfwShortcuts(reddits)
            val filters = File(dir, "threadfilters.db").takeIf { it.exists() }?.let(::importFilters) ?: 0
            val reads = File(dir, "browserhistory.db").takeIf { it.exists() }?.let { importReadMarks(it) } ?: 0
            val settings = File(dir, "settings.xml").takeIf { it.exists() }?.let { importSettings(it) } ?: 0
            RifImportSummary(subs, nsfw, reads, filters, settings, shortcuts)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun extract(uri: Uri, dir: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Couldn't open that file")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name in WANTED) {
                    File(dir, name).outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_ENTRY_BYTES) throw IllegalArgumentException("$name is unexpectedly large")
                            out.write(buf, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun open(file: File) = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    private suspend fun importSubscriptions(file: File): Pair<Int, Int> {
        val rows = mutableListOf<SubscriptionEntity>()
        open(file).use { db ->
            db.rawQuery("SELECT name, nsfw, favorite FROM reddits WHERE frontpage = 1", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    rows += SubscriptionEntity(name, local = true, over18 = c.getInt(1) == 1, favorite = c.getInt(2) == 1)
                }
            }
        }
        val existing = subscriptionDao.allOnce().associateBy { it.name.lowercase() }
        rows.forEach { row ->
            val current = existing[row.name.lowercase()]
            when {
                current == null -> subscriptionDao.upsert(row)
                row.favorite && !current.favorite -> subscriptionDao.setFavorite(row.name, true)
            }
        }
        return rows.size to rows.count { it.over18 }
    }

    /** RIF remembers every visited subreddit; the most-visited NSFW ones become drawer shortcuts. */
    private suspend fun importNsfwShortcuts(file: File): Int {
        val existing = shortcutDao.allOnce().mapTo(HashSet()) { it.name.lowercase() }
        var position = existing.size
        var added = 0
        open(file).use { db ->
            db.rawQuery(
                "SELECT name FROM reddits WHERE nsfw = 1 AND (frontpage IS NULL OR frontpage = 0) " +
                    "ORDER BY access_count DESC, accessed DESC LIMIT $MAX_NSFW_SHORTCUTS", null,
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    if (name.lowercase() in existing) continue
                    shortcutDao.upsert(app.relish.data.local.NsfwShortcutEntity(name, position++))
                    added++
                }
            }
        }
        return added
    }

    private suspend fun importFilters(file: File): Int {
        var count = 0
        open(file).use { db ->
            db.rawQuery("SELECT filter_text, subreddit, filter_type, enabled FROM thread_filters", null).use { c ->
                while (c.moveToNext()) {
                    if (!c.isNull(3) && c.getInt(3) == 0) continue
                    val text = c.getString(0)?.trim().orEmpty()
                    val sub = c.getString(1)?.trim().orEmpty()
                    val kind = c.getString(2)?.lowercase().orEmpty()
                    val (type, value) = when {
                        "domain" in kind -> FilterType.DOMAIN to text
                        "user" in kind || "author" in kind -> FilterType.USER to text
                        "subreddit" in kind -> FilterType.SUBREDDIT to text.ifEmpty { sub }
                        text.isEmpty() && sub.isNotEmpty() -> FilterType.SUBREDDIT to sub
                        else -> FilterType.KEYWORD to text
                    }
                    if (value.isNotEmpty()) {
                        filterDao.insert(FilterEntity(type = type, value = value.lowercase().removePrefix("r/").removePrefix("u/")))
                        count++
                    }
                }
            }
        }
        return count
    }

    private suspend fun importReadMarks(file: File): Int {
        val batch = ArrayList<ReadPostEntity>(1000)
        var count = 0
        open(file).use { db ->
            db.rawQuery("SELECT thread_id, MAX(accessed) FROM uri_history WHERE thread_id IS NOT NULL AND thread_id != '' GROUP BY thread_id", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)?.trim()?.removePrefix("t3_")?.takeIf { it.isNotEmpty() } ?: continue
                    val accessed = if (c.isNull(1)) System.currentTimeMillis() else c.getLong(1).let { if (it < 100_000_000_000L) it * 1000 else it }
                    batch += ReadPostEntity("t3_$id", accessed)
                    if (batch.size == 1000) { readDao.insertAll(batch); count += batch.size; batch.clear() }
                }
            }
        }
        if (batch.isNotEmpty()) { readDao.insertAll(batch); count += batch.size }
        return count
    }

    /** Maps RIF preference keys onto Relish settings; returns how many were applied. */
    private suspend fun importSettings(file: File): Int {
        val values = parseSharedPrefs(file)
        var applied = 0
        suspend fun bool(key: String, apply: suspend (Boolean) -> Unit) {
            values[key]?.toBooleanStrictOrNull()?.let { apply(it); applied++ }
        }
        bool("BACK_GOES_HOME") { prefs.setBackGoesHome(it) }
        bool("CONFIRM_QUIT") { prefs.setQuitConfirmation(it) }
        bool("ENDLESS_REDDIT") { prefs.setAutoLoadNextPage(it) }
        bool("KEEP_SCREEN_ON") { prefs.setKeepScreenOn(it) }
        bool("SHOW_LINK_FLAIR") { prefs.setShowLinkFlair(it) }
        bool("SHOW_COMMENT_FLAIR") { prefs.setShowCommentFlair(it) }
        bool("USE_EXTERNAL_BROWSER") { prefs.setInternalBrowser(!it) }
        bool("NATIVE_IMAGE_ALBUMS") { prefs.setInternalAlbums(it) }
        bool("LEFT_HANDED") { prefs.setLeftHanded(it) }
        bool("COLLAPSE_CHILD_COMMENTS") { prefs.setCompressComments(it) }
        bool("prefsv1_over_18") { prefs.setShowNsfw(it) }
        values["BROWSER_IMAGE_VIEWER"]?.let { prefs.setInternalMediaViewer(it.equals("NATIVE", true)); applied++ }
        values["TEXT_SIZE"]?.uppercase()?.let { size ->
            val mapped = when {
                size.startsWith("SMALL") || size == "TINY" -> FontSize.SMALL
                size.startsWith("MEDIUM") || size == "NORMAL" -> FontSize.MEDIUM
                size.startsWith("LARGE") -> FontSize.LARGE
                size.contains("X") || size.startsWith("HUGE") -> FontSize.EXTRA_LARGE
                else -> null
            }
            mapped?.let { prefs.setFontSize(it); applied++ }
        }
        values["THEME"]?.uppercase()?.let { theme ->
            val mapped = when {
                "BLACK" in theme || "AMOLED" in theme -> ThemeMode.PURE_BLACK
                "DARK" in theme || "NIGHT" in theme -> ThemeMode.DARK
                "LIGHT" in theme -> ThemeMode.LIGHT
                else -> null
            }
            mapped?.let { prefs.setTheme(it); applied++ }
        }
        values["prefsv1_default_comment_sort"]?.let { param ->
            CommentSort.entries.firstOrNull { it.param.equals(param, true) }?.let { prefs.setDefaultCommentSort(it); applied++ }
        }
        return applied
    }

    /** Reads Android SharedPreferences XML into name → value strings. */
    private fun parseSharedPrefs(file: File): Map<String, String> {
        val out = HashMap<String, String>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.getAttributeValue(null, "name")
                    if (name != null) {
                        when (parser.name) {
                            "string" -> out[name] = parser.nextText()
                            "boolean", "int", "long", "float" -> parser.getAttributeValue(null, "value")?.let { out[name] = it }
                        }
                    }
                }
                event = parser.next()
            }
        }
        return out
    }

    companion object {
        private val WANTED = setOf("reddits.db", "threadfilters.db", "browserhistory.db", "settings.xml")
        private const val MAX_ENTRY_BYTES = 200L * 1024 * 1024
        private const val MAX_NSFW_SHORTCUTS = 15
    }
}
