package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text

internal fun JsonObject.memoryStrings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

// §10.12: replacements are explicit and new unrelated global memories stay unchecked.
internal class MemoryImportState(val binding: ProjectBinding) {
    var sources by mutableStateOf<List<JsonObject>?>(null)
        private set
    var skipped by mutableStateOf<List<JsonObject>>(emptyList())
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    val selected = mutableStateMapOf<String, Boolean>()

    val choices: List<JsonObject>
        get() = sources.orEmpty().flatMap { it.objects("candidates") }.filter {
            it.string("state") in setOf("new", "differs") && selected[it.string("id")] == true
        }

    fun accept(result: JsonObject, preselect: Boolean) {
        val next = result.objects("sources")
        val candidates = next.flatMap { it.objects("candidates") }
        val previous = sources.orEmpty().flatMap { it.objects("candidates") }
            .associate { it.string("id") to it.string("state") }
        val unchanged = candidates.filter { it.string("state") in setOf("new", "differs") }
            .filter { previous[it.string("id")] == it.string("state") }.mapNotNull { it.string("id") }.toSet()
        selected.keys.filterNot { it in unchanged }.forEach(selected::remove)
        if (preselect) {
            candidates.filter {
                it.string("state") == "new" && (it.string("scope") == "project" || it.flag("related"))
            }.mapNotNull { it.string("id") }.forEach { selected[it] = true }
        }
        sources = next
        problem = null
    }

    fun change(id: String, checked: Boolean) {
        if (checked) selected[id] = true else selected.remove(id)
    }

    fun payload(): JsonObject = buildJsonObject {
        putJsonArray("items") {
            choices.forEach { candidate ->
                addJsonObject {
                    put("id", requireNotNull(candidate.string("id")))
                    put("replace", candidate.string("state") == "differs")
                }
            }
        }
    }

    suspend fun load(api: ProjectApi, preselect: Boolean) {
        require(api.binding == binding)
        if (loading) return
        loading = true
        problem = null
        try {
            accept(api.get("/agent-memory").jsonObject, preselect)
        } catch (error: Exception) {
            problem = error.problem()
        } finally {
            loading = false
        }
    }

    suspend fun importSelected(api: ProjectApi, onImported: (JsonObject) -> Unit, close: () -> Unit) {
        require(api.binding == binding)
        if (importing || loading || choices.isEmpty()) return
        importing = true
        problem = null
        try {
            val result = api.post("/agent-memory/import", payload()).jsonObject
            onImported(result)
            skipped = result.objects("skipped").filter { it.string("reason") != "same" }
            if (skipped.isEmpty()) close() else {
                selected.clear()
                load(api, preselect = false)
            }
        } catch (error: Exception) {
            problem = error.problem()
        } finally {
            importing = false
        }
    }
}

@Composable
internal fun BoxScope.MemoryImportDialog(tab: ProjectTab, api: ProjectApi, state: MemoryImportState, folder: String,
                                         close: () -> Unit, onImported: (JsonObject) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val choices = state.choices
    val replacing = choices.count { it.string("state") == "differs" }
    val closeWhenIdle = { if (!state.importing) close() }
    LaunchedEffect(state) { if (state.sources == null) state.load(api, preselect = true) }

    WorkOverlay(m["memoryImport.button"], closeWhenIdle,
        Modifier.widthIn(max = 740.dp).fillMaxWidth(.94f).heightIn(max = 700.dp).fillMaxHeight(.9f)) {
        Column(Modifier.fillMaxSize().padding(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    Heading(m["memoryImport.button"], size = StudioTokens.titleSize.value.toInt())
            Muted(m["memoryImport.intro"].replace("{folder}", folder))
            state.problem?.let { problem ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    Box(Modifier.weight(1f)) { ProblemText(problem) }
                    if (state.sources == null || state.skipped.isNotEmpty()) {
                        LibraryButton("refresh-cw", m["retry"], !state.loading && !state.importing) {
                            tab.scope.launch { state.load(api, preselect = state.sources == null) }
                        }
                    }
                }
            }
            Rule()
            ScrollList(Modifier.weight(1f).fillMaxWidth()) {
                if (state.sources == null && state.problem == null) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                        StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                        Muted(m["loading"])
                    }
                }
                state.sources?.forEach { source ->
                    val backend = source.string("backend").orEmpty()
                    val candidates = source.objects("candidates")
                    val fresh = candidates.filter { it.string("state") == "new" }.mapNotNull { it.string("id") }
                    item("header:$backend") {
                        Row(Modifier.fillMaxWidth().padding(top = StudioTokens.space2, bottom = StudioTokens.space1),
                            verticalAlignment = Alignment.CenterVertically) {
                            LibrarySectionTitle(memoryBackendName(backend), Modifier.weight(1f))
                            if (fresh.isNotEmpty()) {
                                val all = fresh.all { state.selected[it] == true }
                                WorkAction(m[if (all) "memoryImport.clearNew" else "memoryImport.selectNew"], null,
                                    enabled = !state.importing && !state.loading) { fresh.forEach { state.change(it, !all) } }
                            }
                        }
                        if (candidates.isEmpty()) LibraryCard {
                            Muted(m["memoryImport.emptySource"])
                            source.memoryStrings("locations").forEach { LibraryCaption(it, mono = true, maxLines = 2) }
                        }
                    }
                    items(candidates, key = { "$backend:${it.string("id")}" }) { candidate ->
                        MemoryImportCandidate(candidate, state)
                    }
                    if (source.flag("truncated")) item("truncated:$backend") { Text(m["memoryImport.listCut"], color = colors.warning) }
                }
                val known = state.sources.orEmpty().flatMap { it.objects("candidates") }.map { it.string("id") }.toSet()
                items(state.skipped.filterNot { it.string("id") in known }) { skipped ->
                    Text(listOfNotNull(skipped.string("name"), memorySkip(skipped, m)).joinToString(" · "), color = colors.warning)
                }
            }
            Rule()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                if (replacing > 0) {
                    Text(m["memoryImport.replacing"].replace("{count}", replacing.toString()), Modifier.weight(1f), color = colors.warning)
                }
                WorkAction(m["cancel"], null, onClick = closeWhenIdle, enabled = !state.importing)
                WorkAction(m["memoryImport.confirm"].replace("{count}", choices.size.toString()), null, primary = true,
                    enabled = choices.isNotEmpty() && !state.importing && !state.loading, busy = state.importing) {
                    tab.scope.launch {
                        if (tab.api?.binding == state.binding) state.importSelected(api, onImported, close)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryImportCandidate(candidate: JsonObject, state: MemoryImportState) {
    val m = localMessages.current
    val colors = localColors.current
    val id = requireNotNull(candidate.string("id"))
    val status = candidate.string("state")
    val selected = state.selected[id] == true
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = StudioTokens.space1)) {
        LibraryCard(selected = selected) {
            WorkCheckbox(candidate.string("name").orEmpty(), selected, status != "same" && !state.importing && !state.loading) {
                state.change(id, it)
            }
            val tags = listOfNotNull(candidate.string("kind"), when {
                candidate.string("scope") != "global" -> null
                candidate.flag("related") -> m["memoryImport.related"]
                else -> m["memoryImport.global"]
            })
            if (tags.isNotEmpty()) LibraryCaption(tags.joinToString(" · "))
            candidate.string("description")?.takeIf(String::isNotBlank)?.let { Text(it) }
            when (status) {
                "same" -> Muted(m["memoryImport.same"])
                "differs" -> Text(m[if (selected) "memoryImport.replaces" else "memoryImport.taken"], color = colors.warning)
            }
            candidate.string("source")?.let { LibraryCaption(it, mono = true, maxLines = 2) }
            state.skipped.firstOrNull { it.string("id") == id }?.let { Text(memorySkip(it, m), color = colors.warning) }
            val content = candidate.string("content").orEmpty()
            if (content.isNotEmpty()) {
                WorkAction(m[if (expanded) "memoryImport.hideText" else "memoryImport.showText"],
                    if (expanded) "chevron-down" else "chevron-right") { expanded = !expanded }
                StudioDisclosure(expanded) { Text(content, fontFamily = localStudioMono.current) }
            }
            if (candidate.flag("truncated")) Text(m["memoryImport.truncated"], color = colors.warning)
        }
    }
}

private fun memoryBackendName(backend: String): String = when (backend) {
    "claude_code" -> "Claude Code"
    "codex" -> "Codex"
    "qwen_code" -> "Qwen Code"
    else -> backend
}

private fun memorySkip(entry: JsonObject, m: Messages): String = when (entry.string("reason")) {
    "exists" -> m["memoryImport.skipExists"]
    "gone" -> m["memoryImport.skipGone"]
    else -> entry.string("error") ?: m["memoryImport.skipError"]
}
