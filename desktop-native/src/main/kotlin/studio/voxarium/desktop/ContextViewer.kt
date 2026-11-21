package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.jewel.ui.component.Text

internal sealed interface NativeContextTarget {
    val endpoint: String

    data class Task(val teamId: String, val taskId: String) : NativeContextTarget {
        override val endpoint = "/teams/${segment(teamId)}/tasks/${segment(taskId)}/context"
    }

    data class Chat(val chatId: String) : NativeContextTarget {
        override val endpoint = "/chats/${segment(chatId)}/context"
    }

    fun fragment(id: String, key: String, view: String): String =
        "$endpoint/fragments/${segment(id)}" + query("key" to key, "view" to view)
}

internal data class NativeContextSource(val data: JsonObject, val id: String, val category: String, val tokens: Long) {
    val key = data.string("text_key")?.takeIf(String::isNotBlank)
    val text = data.string("text").orEmpty()
}

internal data class NativeContextGroup(val category: String, val sources: List<NativeContextSource>, val tokens: Long)

internal fun nativeContextGroups(inventory: JsonObject): List<NativeContextGroup> = inventory.objects("sources")
    .mapNotNull { data ->
        val id = data.string("id") ?: return@mapNotNull null
        val category = data.string("category") ?: return@mapNotNull null
        val count = data.string("tokens")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        NativeContextSource(data, id, category, count)
    }.distinctBy(NativeContextSource::id).groupBy(NativeContextSource::category)
    .map { (category, sources) -> NativeContextGroup(category, sources, sources.sumOf(NativeContextSource::tokens)) }
    .sortedByDescending(NativeContextGroup::tokens)

// §10.4 and §10.9: task and chat sources are read through the tab's own project
// binding. A fragment's numeric slot and opaque key must come from the same view.
@Composable
internal fun BoxScope.NativeContextViewer(tab: ProjectTab, target: NativeContextTarget, close: () -> Unit,
                                         openWeb: (() -> Unit)? = null, revision: String? = null) {
    val api = tab.api ?: return
    val m = localMessages.current
    val scope = rememberCoroutineScope()
    var data by remember(api, target) { mutableStateOf<JsonObject?>(null) }
    var requestedView by remember(api, target) { mutableStateOf("auto") }
    var loadedView by remember(api, target) { mutableStateOf("auto") }
    var refresh by remember(api, target) { mutableIntStateOf(0) }
    var loading by remember(api, target) { mutableStateOf(true) }
    var inspecting by remember(api, target) { mutableStateOf(false) }
    var problem by remember(api, target) { mutableStateOf<Problem?>(null) }

    LaunchedEffect(api, target, requestedView, refresh, revision) {
        if (inspecting) return@LaunchedEffect
        loading = true
        problem = null
        try {
            if (revision != null && data != null) delay(160)
            data = api.get(target.endpoint + query("view" to requestedView)).jsonObject
            loadedView = requestedView
        } catch (error: Exception) {
            problem = error.problem()
        } finally {
            loading = false
        }
    }

    fun inspect() {
        if (inspecting || loading) return
        inspecting = true
        problem = null
        scope.launch {
            try {
                data = api.post("${target.endpoint}/inspect", timeout = Duration.ofSeconds(130)).jsonObject
            } catch (error: Exception) {
                problem = error.problem()
            } finally {
                inspecting = false
            }
        }
    }

    val title = m["contextViewer.title"]
    WorkDialog(title, close, Modifier.widthIn(max = 1160.dp).fillMaxWidth(.96f).fillMaxHeight(.93f)) { dismiss ->
        LibraryHeader(title, m["contextViewer.subtitle"]) {
            WorkAction(m["contextViewer.refresh"], "refresh-cw", iconOnly = true, busy = loading, enabled = !loading && !inspecting) {
                refresh++
            }
            if (openWeb != null) LibraryIconAction("external-link", m["openWeb"], bordered = true, onClick = openWeb)
            LibraryIconAction("x", m["close"], bordered = true, onClick = dismiss)
        }
        problem?.let { Box(Modifier.padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2)) { ProblemText(it) } }
        val inventory = data
        if (inventory == null) {
            if (loading) WorkEmpty(m["contextViewer.loading"], modifier = Modifier.weight(1f).fillMaxWidth())
            return@WorkDialog
        }
        val origin = inventory.string("origin").orEmpty()
        val runtime = inventory.string("basis") == "runtime"
        val number = remember(m.language) { NumberFormat.getIntegerInstance(Locale.forLanguageTag(m.language)) }
        val groups = remember(inventory) { nativeContextGroups(inventory) }
        var activeCategory by remember(api, target, origin, loadedView) { mutableStateOf<String?>(null) }
        val active = groups.find { it.category == activeCategory } ?: groups.firstOrNull { it.category != "unattributed" } ?: groups.firstOrNull()
        ContextSummary(inventory, groups, runtime, contextOrigin(origin, m), number)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            if (loadedView == "studio" || inventory.flag("has_studio_view") && origin.endsWith("_journal")) {
                LibraryButton("layers", m[if (loadedView == "studio") "contextViewer.showJournal" else "contextViewer.showStudio"],
                    enabled = !loading && !inspecting) { requestedView = if (loadedView == "studio") "auto" else "studio" }
            }
            if (inventory.flag("can_inspect")) LibraryButton("cpu", m[if (inspecting) "contextViewer.inspecting" else "contextViewer.inspect"],
                enabled = !loading && !inspecting, onClick = ::inspect)
        }
        if (inventory.flag("partial")) Box(Modifier.padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space1)) { Muted(m["contextViewer.partial"]) }
        if (inventory.flag("can_inspect")) Box(Modifier.padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space1)) {
            Muted(m["contextViewer.inspectHint"])
        }
        if (active == null) {
            WorkEmpty(m["contextViewer.empty"], modifier = Modifier.weight(1f).fillMaxWidth())
            return@WorkDialog
        }
        ContextProportions(groups, active.category, target, number) { activeCategory = it }
        Rule()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sidebar = (maxWidth * .28f).coerceIn(148.dp, 256.dp)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(sidebar).fillMaxHeight()) {
                    LibrarySectionTitle(m["contextViewer.bySource"], Modifier.padding(StudioTokens.space3))
                    ScrollList(Modifier.fillMaxSize()) {
                        items(groups, key = NativeContextGroup::category) { group ->
                            ListRow(contextCategory(group.category, target, m), number.format(group.tokens),
                                group.category == active.category) { activeCategory = group.category }
                        }
                    }
                }
                Rule(vertical = true)
                key(active.category, origin, loadedView) {
                    ContextSources(api, target, active, inventory, number, loadedView,
                        Modifier.weight(1f).fillMaxHeight().studioArrival(distance = StudioTokens.space1, duration = StudioMotion.fast))
                }
            }
        }
    }
}

@Composable
private fun ContextSummary(inventory: JsonObject, groups: List<NativeContextGroup>, runtime: Boolean, origin: String, number: NumberFormat) {
    val m = localMessages.current
    fun amount(key: String): String = inventory.string(key)?.toLongOrNull()?.let(number::format) ?: "—"
    Column(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space5), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            ContextMetric(m["contextViewer.reported"], "${amount("reported_tokens")} / ${amount("context_window")}")
            ContextMetric(m[if (runtime) "contextViewer.attributed" else "contextViewer.estimated"],
                (if (runtime) "" else "≈ ") + amount("estimated_tokens"))
            ContextMetric(m["contextViewer.sources"], number.format(groups.filter { it.category != "unattributed" }.sumOf { it.sources.size }))
        }
        if (origin.isNotBlank()) LibraryCaption(origin)
    }
}

@Composable
private fun ContextMetric(label: String, value: String) {
    Column {
        LibraryCaption(label)
        Text(value)
    }
}

@Composable
private fun ContextProportions(groups: List<NativeContextGroup>, active: String, target: NativeContextTarget,
                               number: NumberFormat, select: (String) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val shown = groups.filter { it.tokens > 0 }
    if (shown.isEmpty()) return
    // Segment widths, hues, and selection emphasis encode the token distribution, not control styling.
    val shape = RoundedCornerShape(StudioTokens.radiusSm)
    Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2)
        .height(StudioTokens.pillMinHeight)
        .clip(shape).selectableGroup().semantics { contentDescription = m["contextViewer.map"] }) {
        shown.forEach { group ->
            val description = "${contextCategory(group.category, target, m)} · ${number.format(group.tokens)}"
            val hue = contextColors[group.category]?.let(::Color) ?: colors.faint
            val fill = hue.copy(alpha = if (group.category == active) 1f else .62f).compositeOver(colors.sheetFill)
            Box(Modifier.weight(group.tokens.toFloat()).fillMaxHeight()
                .studioFocusRing(shape, background = fill).background(fill)
                .border(StudioTokens.borderWidth, colors.surface).selectable(group.category == active, role = Role.Tab) { select(group.category) }
                .semantics { contentDescription = description })
        }
    }
}

@Composable
private fun ContextSources(api: ProjectApi, target: NativeContextTarget, group: NativeContextGroup, inventory: JsonObject,
                           number: NumberFormat, view: String, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    var selected by remember { mutableStateOf<String?>(null) }
    val source = group.sources.find { it.id == selected } ?: group.sources.first()
    var fullText by remember(source.id, source.key, view) { mutableStateOf<String?>(null) }
    var wanted by remember(source.id, source.key, view) { mutableIntStateOf(0) }
    var failed by remember(source.id, source.key, view) { mutableStateOf(false) }
    var fetching by remember(source.id, source.key, view) { mutableStateOf(false) }
    val runtime = inventory.string("basis") == "runtime"

    LaunchedEffect(api, target, source.id, source.key, view, wanted) {
        val key = source.key
        if (runtime || wanted == 0 || key == null) return@LaunchedEffect
        fetching = true
        failed = false
        try {
            fullText = api.get(target.fragment(source.id, key, view)).jsonObject.string("text")
            failed = fullText == null
        } catch (error: Exception) {
            error.problem()
            failed = true
        } finally {
            fetching = false
        }
    }

    Column(modifier.padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        LibrarySectionTitle(contextCategory(group.category, target, m))
        if (group.category == "unattributed") {
            Muted(m["contextViewer.otherHint"])
            return@Column
        }
        if (group.sources.size > 1) {
            LazyRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                itemsIndexed(group.sources, key = { _, part -> part.id }) { index, part ->
                    val label = part.data.string("label")?.takeIf(String::isNotBlank)
                        ?: m["contextViewer.fragment"].replace("{index}", (index + 1).toString())
                    val active = source.id == part.id
                    StudioSelectionRow(active, onClick = { selected = part.id }, modifier = Modifier.widthIn(max = 172.dp), role = Role.Tab) {
                        Text(label, color = if (active) colors.onAccentContainer else colors.text,
                            fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val text = fullText ?: source.text
        val tokens = number.format(source.tokens)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            val kind = source.data.string("source_kind")
            val label = source.data.string("label")?.takeIf(String::isNotBlank) ?: kind.orEmpty()
            LibraryCaption(label, Modifier.weight(1f), maxLines = 2)
            Muted(m[if (runtime) "contextViewer.tokenCount" else "contextViewer.tokenEstimate"].replace("{tokens}", tokens))
        }
        inventory.string("journal_path")?.let { LibraryCaption(it, mono = true, maxLines = 2) }
        source.data.string("origin")?.let { LibraryCaption(it, mono = true, maxLines = 2) }
        source.data.string("representation")?.takeIf { it in setOf("json", "metadata") }?.let { LibraryCaption(m["contextViewer.representations.$it"]) }
        if (source.data.flag("truncated") && fullText == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                if (!runtime && source.key != null) {
                    LibraryButton("file-text", m[if (fetching) "contextViewer.loadingFragment" else "contextViewer.fullText"],
                        enabled = !fetching) { wanted++ }
                }
                Muted(m[if (failed) "contextViewer.fragmentUnavailable" else "contextViewer.truncated"])
            }
        }
        Rule()
        if (text.isEmpty()) {
            Muted(m[if (runtime) "contextViewer.noText" else "contextViewer.emptyText"])
        } else {
            val scroll = rememberScrollState()
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(vertical = StudioTokens.space2)) {
                Text(text, fontFamily = localStudioMono.current)
            }
        }
    }
}

private val contextColors = mapOf(
    "system" to 0xffa78bfaL, "developer" to 0xffb882b8L, "skills" to 0xff56b8edL, "documents" to 0xff4dc9a5L,
    "task" to 0xffe6b866L, "history" to 0xff7a9ef0L, "tools" to 0xffdc92c2L, "thinking" to 0xffb6ba73L,
    "summary" to 0xff75cdd1L, "unattributed" to 0xff969da9L, "memory" to 0xffd3a878L, "environment" to 0xff80b3acL,
    "tool_definitions" to 0xff879bd4L, "agents" to 0xffb3a5d9L, "runtime" to 0xff99aaa4L,
)

private fun contextCategory(category: String, target: NativeContextTarget, m: Messages): String = when {
    category == "task" && target is NativeContextTarget.Chat -> m["contextViewer.chatInput"]
    category in contextColors -> m["contextViewer.categories.$category"]
    else -> category
}

private fun contextOrigin(origin: String, m: Messages): String = when (origin) {
    "studio", "codex_journal", "claude_code_journal", "qwen_journal", "openai_api_journal", "claude_code_runtime", "qwen_runtime" ->
        m["contextViewer.origins.$origin"]
    else -> origin
}
