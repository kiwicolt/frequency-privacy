package app.relish.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "read_posts")
data class ReadPostEntity(@PrimaryKey val fullname: String, val readAt: Long = System.currentTimeMillis())

@Entity(tableName = "hidden_posts")
data class HiddenPostEntity(@PrimaryKey val fullname: String, val hiddenAt: Long = System.currentTimeMillis())

enum class FilterType(val label: String) { KEYWORD("Keyword"), SUBREDDIT("Subreddit"), USER("User"), DOMAIN("Domain") }

@Entity(tableName = "filters", indices = [Index(value = ["type", "value"], unique = true)])
data class FilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: FilterType,
    /** Stored lower-cased; subreddits/users without the r/ or u/ prefix. */
    val value: String,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(@PrimaryKey val query: String, val searchedAt: Long = System.currentTimeMillis())

/**
 * Drawer subscriptions. `local = true` rows are device-only subscriptions (anonymous browsing or
 * subs you don't want on your account); `local = false` rows mirror the account and are replaced on sync.
 */
@Entity(tableName = "subscriptions", primaryKeys = ["name", "local"])
data class SubscriptionEntity(
    val name: String,
    val local: Boolean,
    val over18: Boolean = false,
    val iconUrl: String? = null,
    /** RIF's star: favourites are listed first in the drawer. */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
)

@Entity(tableName = "nsfw_shortcuts")
data class NsfwShortcutEntity(@PrimaryKey val name: String, val position: Int = 0)

@Dao
interface ReadPostDao {
    @Query("SELECT fullname FROM read_posts")
    fun observeAll(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ReadPostEntity)

    @Query("DELETE FROM read_posts")
    suspend fun clear()

    /** Keeps the table bounded; RIF-style read marks don't need to live forever. */
    @Query("DELETE FROM read_posts WHERE readAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface HiddenPostDao {
    @Query("SELECT fullname FROM hidden_posts")
    fun observeAll(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HiddenPostEntity)

    @Query("DELETE FROM hidden_posts WHERE fullname = :fullname")
    suspend fun delete(fullname: String)

    @Query("DELETE FROM hidden_posts")
    suspend fun clear()
}

@Dao
interface FilterDao {
    @Query("SELECT * FROM filters ORDER BY type, value")
    fun observeAll(): Flow<List<FilterEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FilterEntity)

    @Query("DELETE FROM filters WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<SearchHistoryEntity>>

    @Upsert
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
abstract class SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<SubscriptionEntity>>

    @Upsert
    abstract suspend fun upsert(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE name = :name COLLATE NOCASE")
    abstract suspend fun delete(name: String)

    @Query("UPDATE subscriptions SET favorite = :favorite WHERE name = :name COLLATE NOCASE")
    abstract suspend fun setFavorite(name: String, favorite: Boolean)

    @Query("SELECT name FROM subscriptions WHERE favorite = 1")
    protected abstract suspend fun favoriteNames(): List<String>

    @Query("DELETE FROM subscriptions WHERE local = 0")
    protected abstract suspend fun clearAccount()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(items: List<SubscriptionEntity>)

    /** Replaces the account-synced rows, keeping the user's stars. */
    @Transaction
    open suspend fun replaceAccount(items: List<SubscriptionEntity>) {
        val stars = favoriteNames().map { it.lowercase() }.toSet()
        clearAccount()
        insertAll(items.map { it.copy(favorite = it.name.lowercase() in stars) })
    }
}

@Dao
interface NsfwShortcutDao {
    @Query("SELECT * FROM nsfw_shortcuts ORDER BY position, name COLLATE NOCASE")
    fun observeAll(): Flow<List<NsfwShortcutEntity>>

    @Upsert
    suspend fun upsert(entity: NsfwShortcutEntity)

    @Query("DELETE FROM nsfw_shortcuts WHERE name = :name COLLATE NOCASE")
    suspend fun delete(name: String)
}

@Database(
    entities = [
        ReadPostEntity::class, HiddenPostEntity::class, FilterEntity::class, SearchHistoryEntity::class,
        SubscriptionEntity::class, NsfwShortcutEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    abstract fun readPosts(): ReadPostDao
    abstract fun hiddenPosts(): HiddenPostDao
    abstract fun filters(): FilterDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun nsfwShortcuts(): NsfwShortcutDao
}
