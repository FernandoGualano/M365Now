package com.snoopcore.m365now.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY category, name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY category, name")
    suspend fun enabled(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sources: List<SourceEntity>)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sources SET lastSync = :lastSync, lastError = :lastError WHERE id = :id")
    suspend fun updateSyncState(id: Long, lastSync: String, lastError: String)

    @Delete
    suspend fun delete(source: SourceEntity)
}

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY fetchedAt DESC, publishedAt DESC")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(articles: List<ArticleEntity>)

    @Query("UPDATE articles SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    @Query("UPDATE articles SET isRead = NOT isRead WHERE id = :id")
    suspend fun toggleRead(id: String)
}

@Dao
interface RoadmapDao {
    @Query("SELECT * FROM roadmap_items ORDER BY fetchedAt DESC")
    fun observeAll(): Flow<List<RoadmapItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RoadmapItemEntity>)

    @Query("UPDATE roadmap_items SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)
}
