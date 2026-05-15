package com.snoopcore.m365now

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.snoopcore.m365now.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { M365NowApp() }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(AppDatabase.get(app))
    val sources: StateFlow<List<SourceEntity>> = repo.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val articles: StateFlow<List<ArticleEntity>> = repo.articles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val roadmap: StateFlow<List<RoadmapItemEntity>> = repo.roadmap.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var message by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            repo.seedDefaults()
            // Primera carga automática para que la app no quede vacía al instalarla.
            syncAll()
        }
    }

    fun syncAll() = viewModelScope.launch {
        loading = true
        val articleResult = runCatching { repo.syncArticles() }
        val roadmapResult = runCatching { repo.syncRoadmap() }
        loading = false
        val articleMsg = articleResult.fold({ "Artículos: $it" }, { "Artículos error: ${it.message ?: it.javaClass.simpleName}" })
        val roadmapMsg = roadmapResult.fold({ "Roadmap: $it" }, { "Roadmap error: ${it.message ?: it.javaClass.simpleName}" })
        message = "$articleMsg · $roadmapMsg"
    }

    fun addSource(name: String, url: String, category: String) = viewModelScope.launch {
        repo.upsertSource(SourceEntity(name = name, url = url, category = category))
        message = "Fuente agregada"
    }

    fun updateSource(source: SourceEntity, name: String, url: String, category: String) = viewModelScope.launch {
        repo.upsertSource(source.copy(name = name, url = url, category = category, lastError = ""))
        message = "Fuente actualizada"
    }

    fun toggleSource(source: SourceEntity) = viewModelScope.launch { repo.setSourceEnabled(source, !source.enabled) }
    fun deleteSource(source: SourceEntity) = viewModelScope.launch { repo.deleteSource(source); message = "Fuente eliminada" }
    fun testSource(source: SourceEntity) = viewModelScope.launch {
        loading = true
        val result = repo.testSource(source)
        loading = false
        message = result.fold({ "Prueba OK: $it ítems detectados" }, { "Prueba fallida: ${it.message}" })
    }
    fun toggleArticleFavorite(id: String) = viewModelScope.launch { repo.toggleArticleFavorite(id) }
    fun toggleArticleRead(id: String) = viewModelScope.launch { repo.toggleArticleRead(id) }
    fun toggleRoadmapFavorite(id: String) = viewModelScope.launch { repo.toggleRoadmapFavorite(id) }
    fun clearMessage() { message = null }
}

enum class Tab { Home, Articles, Roadmap, Favorites, More }
enum class MorePage { Menu, Sources, Settings, About }

@Composable
fun M365NowApp(vm: MainViewModel = viewModel()) {
    var dark by remember { mutableStateOf(true) }
    val colorScheme = if (dark) darkColorScheme(primary = Color(0xFF38BDF8), background = Color(0xFF050608), surface = Color(0xFF111216)) else lightColorScheme(primary = Color(0xFF0284C7))
    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize()) {
            MainScreen(vm, dark, onToggleTheme = { dark = !dark })
        }
    }
}

@Composable
fun MainScreen(vm: MainViewModel, dark: Boolean, onToggleTheme: () -> Unit) {
    val sources by vm.sources.collectAsState()
    val articles by vm.articles.collectAsState()
    val roadmap by vm.roadmap.collectAsState()
    var tab by remember { mutableStateOf(Tab.Home) }
    var morePage by remember { mutableStateOf(MorePage.Menu) }
    var selectedArticleCategory by remember { mutableStateOf("Todas") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { BottomBar(tab) { selected -> tab = selected; if (selected != Tab.More) morePage = MorePage.Menu; if (selected == Tab.Articles) selectedArticleCategory = "Todas" } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background).padding(18.dp)) {
            Header(title = titleFor(tab, morePage), showBack = tab == Tab.More && morePage != MorePage.Menu, onBack = { morePage = MorePage.Menu }, dark = dark, onToggleTheme = onToggleTheme)
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
            when (tab) {
                Tab.Home -> HomeScreen(articles, roadmap, sources, onSync = vm::syncAll, openArticles = { cat -> selectedArticleCategory = cat; tab = Tab.Articles })
                Tab.Articles -> ArticlesScreen(articles, sources.map { it.category }.distinct(), initialCategory = selectedArticleCategory, favoritesOnly = false, onFavorite = vm::toggleArticleFavorite, onRead = vm::toggleArticleRead)
                Tab.Favorites -> ArticlesScreen(articles.filter { it.isFavorite }, sources.map { it.category }.distinct(), initialCategory = "Todas", favoritesOnly = true, onFavorite = vm::toggleArticleFavorite, onRead = vm::toggleArticleRead)
                Tab.Roadmap -> RoadmapScreen(roadmap, onFavorite = vm::toggleRoadmapFavorite)
                Tab.More -> MoreScreen(morePage, setMorePage = { morePage = it }, sources = sources, onAddSource = vm::addSource, onUpdateSource = vm::updateSource, onTestSource = vm::testSource, onToggleSource = vm::toggleSource, onDeleteSource = vm::deleteSource, dark = dark, onToggleTheme = onToggleTheme)
            }
        }
    }
}

fun titleFor(tab: Tab, page: MorePage): String = when (tab) {
    Tab.Home -> "M365 Now"; Tab.Articles -> "Artículos"; Tab.Roadmap -> "Roadmap"; Tab.Favorites -> "Favoritos"; Tab.More -> when (page) { MorePage.Menu -> "Más"; MorePage.Sources -> "Fuentes RSS"; MorePage.Settings -> "Ajustes"; MorePage.About -> "Acerca de" }
}

@Composable
fun Header(title: String, showBack: Boolean, onBack: () -> Unit, dark: Boolean, onToggleTheme: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") }
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        }
        IconButton(onClick = onToggleTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "Cambiar tema") }
    }
}

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        listOf(Tab.Home to Icons.Default.Dashboard, Tab.Articles to Icons.Default.Article, Tab.Roadmap to Icons.Default.Map, Tab.Favorites to Icons.Default.Star, Tab.More to Icons.Default.MoreHoriz).forEach { (tab, icon) ->
            NavigationBarItem(selected = current == tab, onClick = { onSelect(tab) }, icon = { Icon(icon, null) }, label = { Text(tab.name.replace("Articles", "Artículos").replace("Favorites", "Favoritos")) })
        }
    }
}

@Composable
fun HomeScreen(articles: List<ArticleEntity>, roadmap: List<RoadmapItemEntity>, sources: List<SourceEntity>, onSync: () -> Unit, openArticles: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatCard("No leídos", articles.count { !it.isRead }.toString(), Icons.Default.Notifications, isDanger = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallStat("Favoritos", (articles.count { it.isFavorite } + roadmap.count { it.isFavorite }).toString(), Icons.Default.Star, Modifier.weight(1f))
                SmallStat("Artículos", articles.size.toString(), Icons.Default.Article, Modifier.weight(1f))
                SmallStat("Roadmap", roadmap.size.toString(), Icons.Default.Map, Modifier.weight(1f))
            }
        }
        item { Button(onClick = onSync, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Sincronizar") } }
        item { SectionLabel("Por categoría") }
        val categories = sources.map { it.category }.distinct().ifEmpty { DefaultData.categories }
        items(categories.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { cat -> CategoryTile(cat, articles.count { it.category == cat }, Modifier.weight(1f)) { openArticles(cat) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { SectionLabel("Últimas novedades") }
        items(articles.take(5)) { ArticleCard(it, onFavorite = {}, onRead = {}) }
    }
}

@Composable
fun ArticlesScreen(articles: List<ArticleEntity>, categories: List<String>, initialCategory: String, favoritesOnly: Boolean, onFavorite: (String) -> Unit, onRead: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    val list = articles.filter { category == "Todas" || it.category == category }.filter { (it.title + it.summary + it.sourceName).contains(query, ignoreCase = true) }
    Column {
        OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Buscar...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
        LazyRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Todas") + categories) { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(list) { ArticleCard(it, onFavorite = { onFavorite(it.id) }, onRead = { onRead(it.id) }) } }
    }
}

@Composable
fun RoadmapScreen(roadmap: List<RoadmapItemEntity>, onFavorite: (String) -> Unit) {
    var product by remember { mutableStateOf("Todos") }
    var status by remember { mutableStateOf("Todos") }
    val products = roadmap.map { it.product }.distinct().sorted()
    val statuses = roadmap.map { it.status }.distinct().sorted()
    val list = roadmap.filter { product == "Todos" || it.product == product }.filter { status == "Todos" || it.status == status }
    Column {
        Text("Productos informados por Microsoft Roadmap", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf("Todos") + products) { p -> FilterChip(selected = product == p, onClick = { product = p }, label = { Text(p) }) } }
        LazyRow(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf("Todos") + statuses) { s -> FilterChip(selected = status == s, onClick = { status = s }, label = { Text(s) }) } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(list) { RoadmapCard(it) { onFavorite(it.id) } } }
    }
}

@Composable
fun MoreScreen(page: MorePage, setMorePage: (MorePage) -> Unit, sources: List<SourceEntity>, onAddSource: (String, String, String) -> Unit, onUpdateSource: (SourceEntity, String, String, String) -> Unit, onTestSource: (SourceEntity) -> Unit, onToggleSource: (SourceEntity) -> Unit, onDeleteSource: (SourceEntity) -> Unit, dark: Boolean, onToggleTheme: () -> Unit) {
    when (page) {
        MorePage.Menu -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MenuRow("Fuentes RSS", "Agregar, editar o probar fuentes", Icons.Default.RssFeed) { setMorePage(MorePage.Sources) }
            MenuRow("Ajustes", "Sincronización, tema, notificaciones", Icons.Default.Settings) { setMorePage(MorePage.Settings) }
            MenuRow("Acerca de", "Versión, créditos, privacidad", Icons.Default.Info) { setMorePage(MorePage.About) }
            Text("Esta aplicación no está afiliada, respaldada ni mantenida por Microsoft.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        }
        MorePage.Sources -> SourcesScreen(sources, onAddSource, onUpdateSource, onTestSource, onToggleSource, onDeleteSource)
        MorePage.Settings -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Button(onClick = onToggleTheme) { Text("Cambiar a modo ${if (dark) "claro" else "oscuro"}") }; Text("Notificaciones y sincronización automática quedan preparadas para v1.1.") }
        MorePage.About -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("M365 Now", fontWeight = FontWeight.Black); Text("Versión 1.0.3 MVP"); Text("Sin backend propio. RSS configurable y Roadmap público de Microsoft.") }
    }
}

@Composable
fun SourcesScreen(sources: List<SourceEntity>, onAdd: (String, String, String) -> Unit, onUpdate: (SourceEntity, String, String, String) -> Unit, onTest: (SourceEntity) -> Unit, onToggle: (SourceEntity) -> Unit, onDelete: (SourceEntity) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<SourceEntity?>(null) }
    var delete by remember { mutableStateOf<SourceEntity?>(null) }
    Column {
        Button(onClick = { showAdd = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("Agregar fuente RSS") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            items(sources) { s -> SourceCard(s, onTest = { onTest(s) }, onEdit = { edit = s }, onToggle = { onToggle(s) }, onDelete = { delete = s }) }
        }
    }
    if (showAdd) SourceDialog(title = "Nueva fuente", categories = DefaultData.categories, onDismiss = { showAdd = false }) { n, u, c -> onAdd(n, u, c); showAdd = false }
    edit?.let { src -> SourceDialog(title = "Editar fuente", initial = src, categories = DefaultData.categories, onDismiss = { edit = null }) { n, u, c -> onUpdate(src, n, u, c); edit = null } }
    delete?.let { src -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("¿Borrar fuente?") }, text = { Text("Se eliminará ${src.name}. Esta acción no se puede deshacer.") }, confirmButton = { Button(onClick = { onDelete(src); delete = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar") } }, dismissButton = { OutlinedButton(onClick = { delete = null }) { Text("Cancelar") } }) }
}

@Composable
fun SourceDialog(title: String, initial: SourceEntity? = null, categories: List<String>, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: categories.first()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") })
            OutlinedTextField(url, { url = it }, label = { Text("URL RSS/Atom") })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(categories) { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) } }
        }
    }, confirmButton = { Button(enabled = name.isNotBlank() && url.isNotBlank(), onClick = { onSave(name, url, category) }) { Text("Guardar") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isDanger: Boolean = false) { ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black) }; Icon(icon, null, modifier = Modifier.size(46.dp)) } } }
@Composable fun SmallStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) { ElevatedCard(modifier, shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(12.dp)) { Icon(icon, null); Spacer(Modifier.height(18.dp)); Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black); Text(label, style = MaterialTheme.typography.labelSmall) } } }
@Composable fun SectionLabel(text: String) { Text(text.uppercase(), modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Black) }
@Composable
fun CategoryTile(category: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            ProductBadge(category, size = 42.dp)
            Spacer(Modifier.height(12.dp))
            Text(category, fontWeight = FontWeight.Black)
            Text("$count artículos", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ArticleCard(a: ArticleEntity, onFavorite: () -> Unit, onRead: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ElevatedCard(onClick = { if (a.url.isNotBlank()) uriHandler.openUri(a.url) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            ProductBadge(a.category, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = colorFor(a.category), fontWeight = FontWeight.Black)
                Text(a.title, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${a.sourceName} · ${a.publishedAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(a.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onFavorite) { Icon(if (a.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (a.isFavorite) Color(0xFFFFB900) else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun RoadmapCard(r: RoadmapItemEntity, onFavorite: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ElevatedCard(onClick = { if (r.url.isNotBlank()) uriHandler.openUri(r.url) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(r.product) })
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text(r.status) })
            }
            Text(r.title, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp))
            Text(r.description, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ID ${r.roadmapId}", style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = onFavorite) { Icon(if (r.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (r.isFavorite) Color(0xFFFFB900) else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun SourceCard(s: SourceEntity, onTest: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) { ElevatedCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(s.name, fontWeight = FontWeight.Black); Text(s.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); AssistChip(onClick = {}, label = { Text(s.category) }) }; Switch(checked = s.enabled, onCheckedChange = { onToggle() }) }; if (s.lastError.isNotBlank()) Text("⚠ ${s.lastError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onTest) { Text("Probar") }; OutlinedButton(onClick = onEdit) { Text("Editar") }; OutlinedButton(onClick = onDelete) { Text("Borrar") } } } } }

@Composable
fun ProductBadge(category: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).background(colorFor(category).copy(alpha = 0.18f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(productInitials(category), color = colorFor(category), fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
    }
}

fun productInitials(category: String): String = when (category) {
    "Exchange" -> "EX"
    "Teams / UC" -> "T"
    "Entra ID" -> "ID"
    "Intune" -> "IN"
    "Security" -> "SEC"
    "Copilot" -> "AI"
    "Azure" -> "AZ"
    "OneDrive / SPO" -> "SP"
    "MS Graph / PS" -> "PS"
    "Community" -> "CM"
    else -> "M365"
}

fun colorFor(category: String): Color = when (category) {
    "Exchange" -> Color(0xFF0078D4)
    "Teams / UC" -> Color(0xFF6264A7)
    "Entra ID" -> Color(0xFF0078D4)
    "Intune" -> Color(0xFF00B294)
    "Security" -> Color(0xFF6B7280)
    "Copilot" -> Color(0xFF8661C5)
    "Azure" -> Color(0xFF0078D4)
    "OneDrive / SPO" -> Color(0xFF038387)
    "MS Graph / PS" -> Color(0xFF5391FE)
    "Community" -> Color(0xFF8B5CF6)
    else -> Color(0xFFF7630C)
}

fun iconFor(category: String): String = productInitials(category)
@Composable fun MenuRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { ElevatedCard(onClick = onClick, shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.size(34.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Black); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Default.ChevronRight, null) } } }

