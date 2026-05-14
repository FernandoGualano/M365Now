package com.snoopcore.m365now.network

import android.util.Xml
import com.snoopcore.m365now.data.ArticleEntity
import com.snoopcore.m365now.data.RoadmapItemEntity
import com.snoopcore.m365now.data.SourceEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NetworkClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "M365Now/1.0")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            return response.body?.string().orEmpty()
        }
    }
}

data class ParsedFeedItem(val title: String, val summary: String, val url: String, val publishedAt: String)

object RssParser {
    fun parse(xmlText: String): List<ParsedFeedItem> {
        val parser = Xml.newPullParser()
        parser.setInput(xmlText.reader())
        val items = mutableListOf<ParsedFeedItem>()
        var event = parser.eventType
        var inItem = false
        var title = ""
        var summary = ""
        var link = ""
        var date = ""

        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (name == "item" || name == "entry") {
                        inItem = true
                        title = ""; summary = ""; link = ""; date = ""
                    } else if (inItem) {
                        when (name) {
                            "title" -> title = parser.nextTextSafe()
                            "description", "summary", "content" -> if (summary.isBlank()) summary = parser.nextTextSafe().stripHtml()
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                link = href ?: parser.nextTextSafe()
                            }
                            "pubdate", "published", "updated" -> date = parser.nextTextSafe()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((name == "item" || name == "entry") && inItem) {
                        if (title.isNotBlank() && link.isNotBlank()) {
                            items += ParsedFeedItem(title.trim(), summary.trim(), link.trim(), date.trim())
                        }
                        inItem = false
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun XmlPullParser.nextTextSafe(): String = try { nextText() } catch (_: Exception) { "" }
    private fun String.stripHtml(): String = replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
}

object RoadmapParser {
    private const val ROADMAP_BASE = "https://www.microsoft.com/microsoft-365/roadmap?searchterms="

    fun parse(json: String): List<RoadmapItemEntity> {
        val array = JSONArray(json)
        val now = System.currentTimeMillis()
        return buildList {
            for (i in 0 until minOf(array.length(), 250)) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.firstString("id", "featureId", "roadmapId") ?: continue
                val title = item.firstString("title", "featureName", "name") ?: continue
                val desc = item.firstString("description", "featureDescription", "body") ?: ""
                val status = item.firstString("status", "releaseStatus") ?: "Unknown"
                val updated = item.firstString("modified", "lastModifiedDate", "updatedAt", "created") ?: ""
                val tags = item.optJSONObject("tagsContainer")
                val product = tags?.firstArrayValue("products") ?: item.firstArrayValue("products") ?: item.firstString("product") ?: "Microsoft 365"
                val phase = tags?.firstArrayValue("releasePhase") ?: item.firstArrayValue("releasePhase") ?: ""
                add(RoadmapItemEntity(id = id, roadmapId = id, title = title, description = desc, product = product, status = status, releasePhase = phase, url = ROADMAP_BASE + id, updatedAt = updated, fetchedAt = now))
            }
        }
    }

    private fun JSONObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() && it != "null" } }
    private fun JSONObject.firstArrayValue(key: String): String? = optJSONArray(key)?.optString(0)?.takeIf { it.isNotBlank() }
}

fun SourceEntity.toArticleEntities(items: List<ParsedFeedItem>): List<ArticleEntity> {
    val now = System.currentTimeMillis()
    return items.map {
        val id = sha256(url.normalizeUrl() + "|" + it.url.normalizeUrl() + "|" + it.title)
        ArticleEntity(id = id, title = it.title, summary = it.summary, url = it.url, sourceName = name, sourceId = this.id, category = category, publishedAt = it.publishedAt, fetchedAt = now)
    }
}

fun String.normalizeUrl(): String = trim().lowercase().removeSuffix("/")

fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
