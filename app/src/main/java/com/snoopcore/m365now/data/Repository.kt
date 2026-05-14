package com.snoopcore.m365now.data

import com.snoopcore.m365now.network.FeedParser
import com.snoopcore.m365now.network.NetworkClient
import com.snoopcore.m365now.network.RoadmapParser
import com.snoopcore.m365now.network.toArticleEntities
import kotlinx.coroutines.flow.Flow

class Repository(private val db: AppDatabase) {
    private val network = NetworkClient()
    val sources: Flow<List<SourceEntity>> = db.sourceDao().observeAll()
    val articles: Flow<List<ArticleEntity>> = db.articleDao().observeAll()
    val roadmap: Flow<List<RoadmapItemEntity>> = db.roadmapDao().observeAll()

    suspend fun seedDefaults() {
        if (db.sourceDao().count() == 0) db.sourceDao().insertAll(DefaultData.sources)
    }

    suspend fun upsertSource(source: SourceEntity) = db.sourceDao().upsert(source)
    suspend fun deleteSource(source: SourceEntity) = db.sourceDao().delete(source)
    suspend fun setSourceEnabled(source: SourceEntity, enabled: Boolean) = db.sourceDao().setEnabled(source.id, enabled)
    suspend fun toggleArticleFavorite(id: String) = db.articleDao().toggleFavorite(id)
    suspend fun toggleArticleRead(id: String) = db.articleDao().toggleRead(id)
    suspend fun toggleRoadmapFavorite(id: String) = db.roadmapDao().toggleFavorite(id)

    suspend fun testSource(source: SourceEntity): Result<Int> = runCatching {
        val body = network.getText(source.url)
        val items = FeedParser.parse(body, source.url)
        if (items.isEmpty()) throw IllegalStateException("No se detectaron ítems RSS/HTML en la fuente")
        db.sourceDao().updateSyncState(source.id, "recién", "")
        items.size
    }.onFailure { error ->
        db.sourceDao().updateSyncState(source.id, "recién", error.message ?: error.javaClass.simpleName)
    }

    suspend fun syncArticles(): Int {
        var imported = 0
        db.sourceDao().enabled().forEach { source ->
            val result = runCatching {
                val body = network.getText(source.url)
                val parsed = FeedParser.parse(body, source.url).take(50)
                if (parsed.isEmpty()) throw IllegalStateException("No se detectaron ítems RSS/HTML")
                db.articleDao().insertIgnore(source.toArticleEntities(parsed))
                db.sourceDao().updateSyncState(source.id, "recién", "")
                parsed.size
            }
            imported += result.getOrDefault(0)
            result.exceptionOrNull()?.let { db.sourceDao().updateSyncState(source.id, "recién", it.message ?: it.javaClass.simpleName) }
        }
        return imported
    }

    suspend fun syncRoadmap(): Int {
        val json = network.getText("https://www.microsoft.com/releasecommunications/api/v1/m365")
        val items = RoadmapParser.parse(json)
        if (items.isEmpty()) throw IllegalStateException("Roadmap sin elementos")
        db.roadmapDao().upsertAll(items)
        return items.size
    }
}

object DefaultData {
    val categories = listOf("Microsoft 365", "Exchange", "Teams / UC", "Entra ID", "Intune", "Security", "Copilot", "Azure", "OneDrive / SPO", "MS Graph / PS", "Community")
    val sources = listOf(
        SourceEntity(name = "Microsoft Teams Blog", category = "Teams / UC", url = "https://techcommunity.microsoft.com/category/microsoftteams/blog/microsoftteamsblog"),
        SourceEntity(name = "Jeff Schertz Blog", category = "Teams / UC", url = "https://blog.schertz.name/feed/"),
        SourceEntity(name = "Exchange Team Blog", category = "Exchange", url = "https://techcommunity.microsoft.com/category/exchange/blog/exchange"),
        SourceEntity(name = "Microsoft Entra Blog", category = "Entra ID", url = "https://techcommunity.microsoft.com/category/microsoft-entra/blog/microsoft-entra-blog"),
        SourceEntity(name = "Microsoft Intune Blog", category = "Intune", url = "https://techcommunity.microsoft.com/category/microsoft-intune/blog/microsoft-intune-blog"),
        SourceEntity(name = "Microsoft Security Blog", category = "Security", url = "https://www.microsoft.com/en-us/security/blog/feed/"),
        SourceEntity(name = "OneDrive Blog", category = "OneDrive / SPO", url = "https://techcommunity.microsoft.com/category/onedrive/blog/onedriveblog"),
        SourceEntity(name = "SharePoint Blog", category = "OneDrive / SPO", url = "https://techcommunity.microsoft.com/category/sharepoint/blog/sharepoint"),
        SourceEntity(name = "Microsoft Graph Blog", category = "MS Graph / PS", url = "https://devblogs.microsoft.com/microsoft365dev/category/microsoft-graph/feed/"),
        SourceEntity(name = "PowerShell Team Blog", category = "MS Graph / PS", url = "https://devblogs.microsoft.com/powershell/feed/"),
        SourceEntity(name = "Practical 365", category = "Community", url = "https://practical365.com/feed/")
    )
}
