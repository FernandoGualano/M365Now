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
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) M365Now/1.0.3")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, application/json;q=0.9, */*;q=0.8")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            return response.body?.string().orEmpty()
        }
    }
}

data class ParsedFeedItem(val title: String, val summary: String, val url: String, val publishedAt: String)

object FeedParser {
    fun parse(text: String, sourceUrl: String): List<ParsedFeedItem> {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("<") && (trimmed.contains("<rss", true) || trimmed.contains("<feed", true) || trimmed.contains("<rdf", true))) {
            RssParser.parse(trimmed)
        } else {
            HtmlParser.parse(trimmed, sourceUrl)
        }
    }
}

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
            val name = parser.name?.lowercase()?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (name == "item" || name == "entry") {
                        inItem = true
                        title = ""; summary = ""; link = ""; date = ""
                    } else if (inItem) {
                        when (name) {
                            "title" -> title = parser.nextTextSafe().stripHtml()
                            "description", "summary", "content", "encoded" -> if (summary.isBlank()) summary = parser.nextTextSafe().stripHtml()
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                val rel = parser.getAttributeValue(null, "rel")
                                if (href != null && (rel == null || rel == "alternate")) link = href else if (href == null && link.isBlank()) link = parser.nextTextSafe()
                            }
                            "guid" -> if (link.isBlank()) link = parser.nextTextSafe()
                            "pubdate", "published", "updated", "date" -> if (date.isBlank()) date = parser.nextTextSafe()
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
        return items.distinctBy { it.url.normalizeUrl() }
    }

    private fun XmlPullParser.nextTextSafe(): String = try { nextText() } catch (e: Exception) { "" }
}

object HtmlParser {
    fun parse(html: String, baseUrl: String): List<ParsedFeedItem> {
        // Fallback simple para páginas HTML cuando el origen no expone un XML RSS directo.
        // Busca anchors con títulos suficientemente largos y evita links de navegación.
        val anchorRegex = Regex("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
        return anchorRegex.findAll(html).mapNotNull { match ->
            val rawHref = match.groupValues[1]
            val title = match.groupValues[2].stripHtml().decodeHtml().trim()
            val href = rawHref.toAbsoluteUrl(baseUrl)
            if (title.length < 18) return@mapNotNull null
            if (!href.startsWith("http")) return@mapNotNull null
            if (href.contains("#") || href.contains("signin", true) || href.contains("register", true)) return@mapNotNull null
            val looksLikeArticle = href.contains("/blog/", true) || href.contains("/p/", true) || href.contains("/t5/", true) || href.contains("/20", true)
            if (!looksLikeArticle) return@mapNotNull null
            ParsedFeedItem(title = title, summary = "", url = href, publishedAt = "")
        }.distinctBy { it.url.normalizeUrl() }.take(50).toList()
    }
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
                val product = tags?.firstTagName("products") ?: item.firstTagName("products") ?: item.firstString("product") ?: "Microsoft 365"
                val phase = tags?.firstTagName("releasePhase") ?: item.firstTagName("releasePhase") ?: ""
                add(RoadmapItemEntity(id = id, roadmapId = id, title = title, description = desc.stripHtml(), product = product, status = status, releasePhase = phase, url = ROADMAP_BASE + id, updatedAt = updated, fetchedAt = now))
            }
        }
    }

    private fun JSONObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() && it != "null" } }
    private fun JSONObject.firstTagName(key: String): String? {
        val array = optJSONArray(key) ?: return null
        val first = array.opt(0) ?: return null
        return when (first) {
            is JSONObject -> first.optString("tagName").takeIf { it.isNotBlank() }
            is String -> first.takeIf { it.isNotBlank() }
            else -> first.toString().takeIf { it.isNotBlank() }
        }
    }
}

fun SourceEntity.toArticleEntities(items: List<ParsedFeedItem>): List<ArticleEntity> {
    val now = System.currentTimeMillis()
    return items.map {
        val id = sha256(url.normalizeUrl() + "|" + it.url.normalizeUrl() + "|" + it.title)
        ArticleEntity(id = id, title = it.title, summary = it.summary, url = it.url, sourceName = name, sourceId = this.id, category = category, publishedAt = it.publishedAt, fetchedAt = now)
    }
}

fun String.normalizeUrl(): String = trim().lowercase().removeSuffix("/")

fun String.stripHtml(): String = replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]*>"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

fun String.decodeHtml(): String = this
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", " ")

private fun String.toAbsoluteUrl(baseUrl: String): String {
    if (startsWith("http://", true) || startsWith("https://", true)) return this
    val base = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(baseUrl)?.groupValues?.get(1) ?: return this
    return if (startsWith("/")) base + this else baseUrl.substringBeforeLast('/', baseUrl) + "/" + this
}

fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
