package com.snoopcore.m365now.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val category: String,
    val enabled: Boolean = true,
    val lastSync: String = "Nunca",
    val lastError: String = ""
)

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val sourceName: String,
    val sourceId: Long,
    val category: String,
    val publishedAt: String,
    val fetchedAt: Long,
    val isRead: Boolean = false,
    val isFavorite: Boolean = false
)

@Entity(tableName = "roadmap_items")
data class RoadmapItemEntity(
    @PrimaryKey val id: String,
    val roadmapId: String,
    val title: String,
    val description: String,
    val product: String,
    val status: String,
    val releasePhase: String,
    val url: String,
    val updatedAt: String,
    val fetchedAt: Long,
    val isFavorite: Boolean = false
)
