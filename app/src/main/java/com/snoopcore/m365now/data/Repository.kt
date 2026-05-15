package com.snoopcore.m365now.data

import com.snoopcore.m365now.network.FeedParser
import com.snoopcore.m365now.network.NetworkClient
import com.snoopcore.m365now.network.RoadmapParser
import com.snoopcore.m365now.network.toArticleEntities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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
        // OkHttp.execute() es bloqueante. En Android no puede ejecutarse en el hilo principal.
        val body = withContext(Dispatchers.IO) { network.getText(source.url) }
        val items = withContext(Dispatchers.Default) { FeedParser.parse(body, source.url) }
        if (items.isEmpty()) throw IllegalStateException("No se detectaron ítems RSS/HTML en la fuente")
        db.sourceDao().updateSyncState(source.id, "recién", "")
        items.size
    }.onFailure { error ->
        db.sourceDao().updateSyncState(source.id, "recién", error.userMessage())
    }

    suspend fun syncArticles(): Int {
        var imported = 0
        db.sourceDao().enabled().forEach { source ->
            val result = runCatching {
                // La descarga y el parseo se ejecutan fuera del Main Thread para evitar
                // NetworkOnMainThreadException y bloqueos de UI.
                val body = withContext(Dispatchers.IO) { network.getText(source.url) }
                val parsed = withContext(Dispatchers.Default) { FeedParser.parse(body, source.url).take(50) }
                if (parsed.isEmpty()) throw IllegalStateException("No se detectaron ítems RSS/HTML")
                db.articleDao().insertIgnore(source.toArticleEntities(parsed))
                db.sourceDao().updateSyncState(source.id, "recién", "")
                parsed.size
            }
            imported += result.getOrDefault(0)
            result.exceptionOrNull()?.let { db.sourceDao().updateSyncState(source.id, "recién", it.userMessage()) }
        }
        return imported
    }

    suspend fun syncRoadmap(): Int {
        val json = withContext(Dispatchers.IO) { network.getText("https://www.microsoft.com/releasecommunications/api/v1/m365") }
        val items = withContext(Dispatchers.Default) { RoadmapParser.parse(json) }
        if (items.isEmpty()) throw IllegalStateException("Roadmap sin elementos")
        db.roadmapDao().upsertAll(items)
        return items.size
    }

    private fun Throwable.userMessage(): String {
        val base = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
        return when (this) {
            is android.os.NetworkOnMainThreadException -> "Error interno: red ejecutada en el hilo principal"
            is java.net.UnknownHostException -> "Sin conexión o DNS no resuelve el host"
            is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
            else -> base
        }
    }
}

object DefaultData {
    val categories = listOf("Microsoft 365", "Exchange", "Teams / UC", "Entra ID", "Intune", "Security", "Copilot", "Azure", "OneDrive / SPO", "MS Graph / PS", "Community")
    val sources = listOf(
        SourceEntity(name = "Microsoft 365 Blog", category = "Microsoft 365", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=microsoft_365blog"),
        SourceEntity(name = "Microsoft Teams Blog", category = "Teams / UC", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=MicrosoftTeamsBlog"),
        SourceEntity(name = "Skype for Business Blog", category = "Teams / UC", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=Skype_for_Business_Blog"),
        SourceEntity(name = "Jeff Schertz Blog", category = "Teams / UC", url = "https://blog.schertz.name/feed/"),
        SourceEntity(name = "Exchange Team Blog", category = "Exchange", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=Exchange"),
        SourceEntity(name = "Microsoft Entra Blog", category = "Entra ID", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=microsoft-entra-blog"),
        SourceEntity(name = "Microsoft Intune Blog", category = "Intune", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=microsoftintuneblog"),
        SourceEntity(name = "Intune Customer Success", category = "Intune", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=IntuneCustomerSuccess"),
        SourceEntity(name = "Microsoft Security Blog", category = "Security", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=microsoft-security-blog"),
        SourceEntity(name = "OneDrive Blog", category = "OneDrive / SPO", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=OneDriveBlog"),
        SourceEntity(name = "SharePoint Blog", category = "OneDrive / SPO", url = "https://techcommunity.microsoft.com/t5/s/gxcuf89792/rss/board?board.id=SPBlog"),
        SourceEntity(name = "Microsoft Graph Blog", category = "MS Graph / PS", url = "https://devblogs.microsoft.com/microsoft365dev/category/microsoft-graph/feed/"),
        SourceEntity(name = "PowerShell Team Blog", category = "MS Graph / PS", url = "https://devblogs.microsoft.com/powershell/feed/"),
        SourceEntity(name = "Practical 365", category = "Community", url = "https://practical365.com/feed/")
    )
}
