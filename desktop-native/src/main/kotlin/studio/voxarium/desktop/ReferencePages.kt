package studio.voxarium.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticalScrollbar

// §10.10 and §10.12: preserve drafts with their project tab and the version they started from.
internal class ReferencePageState {
    internal var search by mutableStateOf(TextFieldValue())
    internal var scopeFilter by mutableStateOf("")
    internal var backendFilter by mutableStateOf("")
    internal var showingIndex by mutableStateOf(false)
    internal var catalog by mutableStateOf<JsonObject?>(null)
    internal var catalogBinding: ProjectBinding? = null
    internal var selected by mutableStateOf<String?>(null)
    internal var detail by mutableStateOf<ReferenceDetail?>(null)
    internal var editor by mutableStateOf<ReferenceEditor?>(null)
    internal var creation by mutableStateOf<ReferenceCreation?>(null)
    internal var listError by mutableStateOf<Problem?>(null)
    internal var detailError by mutableStateOf<Problem?>(null)
    internal var writeError by mutableStateOf<Problem?>(null)
    internal var working by mutableStateOf(false)
    internal var refresh by mutableIntStateOf(0)
    internal var memoryImport by mutableStateOf<MemoryImportState?>(null)
    internal var importedMemoryCount by mutableIntStateOf(0)

    val hasUnsavedChanges: Boolean get() = editor?.dirty == true || creation?.dirty == true || memoryImport?.importing == true

    internal fun select(name: String) {
        if (name == selected && creation == null) return
        selected = name
        detail = null
        detailError = null
        writeError = null
        editor = null
        creation = null
    }
}

internal data class ReferenceDetail(val name: String, val data: JsonObject, val binding: ProjectBinding)

internal class ReferenceEditor(val original: ReferenceDetail, val revision: String) {
    private val initialDescription = original.data.string("description").orEmpty()
    private val initialContent = original.data.string("content").orEmpty()
    var description by mutableStateOf(TextFieldValue(initialDescription))
    var content by mutableStateOf(TextFieldValue(initialContent))
    val dirty: Boolean get() = description.text != initialDescription || content.text != initialContent
}

internal class ReferenceCreation(val binding: ProjectBinding) {
    var name by mutableStateOf(TextFieldValue())
    var description by mutableStateOf(TextFieldValue())
    var content by mutableStateOf(TextFieldValue())
    val dirty: Boolean get() = name.text.isNotEmpty() || description.text.isNotEmpty() || content.text.isNotEmpty()
}

private fun JsonObject.referenceStrings(key: String) =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.referenceTitle() = string("display_name") ?: requireNotNull(string("name"))

private fun JsonObject.referenceScope() = when (string("scope")) {
    "project" -> "project"
    "global" -> "global"
    else -> "studio"
}

private fun enqueueReferenceWrite(
    tab: ProjectTab,
    state: ReferencePageState,
    expected: ProjectBinding,
    editing: Boolean,
    write: suspend ProjectApi.() -> Unit,
) {
    if (state.working) return
    val api = tab.api ?: return
    if (api.binding != expected) {
        state.writeError = Problem("referenceBindingChanged")
        return
    }
    state.working = true
    state.writeError = null
    tab.scope.launch {
        try {
            api.write()
            state.refresh++
        } catch (error: Exception) {
            state.writeError = error.problem(
                if (editing && error is ApiFailure && error.status == 409) "referenceConflict" else "requestFailed"
            )
        } finally {
            state.working = false
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun ReferencePage(tab: ProjectTab, skills: Boolean, modifier: Modifier = Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val state = if (skills) tab.skillsPage else tab.memoryPage
    val api = tab.api
    val collection = if (skills) "/skills" else "/memory"
    val entriesKey = if (skills) "skills" else "entries"
    val selected = state.selected
    val refresh = state.refresh
    var refreshing by remember(api, tab.pageRefresh, refresh) { mutableStateOf(api != null) }

    LaunchedEffect(api, tab.pageRefresh, refresh) {
        if (api == null) return@LaunchedEffect
        if (state.catalogBinding != api.binding) {
            state.catalog = null
            state.catalogBinding = api.binding
            state.memoryImport = null
            state.importedMemoryCount = 0
        }
        state.listError = null
        try {
            val overview = api.get(collection).jsonObject
            if (state.refresh == refresh) state.catalog = overview
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            state.listError = error.problem()
        } finally {
            refreshing = false
        }
    }
    LaunchedEffect(api, selected, tab.pageRefresh, refresh) {
        if (api == null || selected == null) return@LaunchedEffect
        state.detailError = null
        try {
            val detail = api.get("$collection/"+segment(selected)).jsonObject
            if (state.selected == selected && state.refresh == refresh) {
                state.detail = ReferenceDetail(selected, detail, api.binding)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (state.selected == selected) {
                state.detailError = error.problem()
                state.detail = null
            }
        }
    }

    val catalog = state.catalog
    val folder = catalog?.string("path")
    val entries = catalog?.objects(entriesKey).orEmpty()
    val query = state.search.text.trim()
    val filtered = entries.filter { item ->
        val matches = query.isEmpty() || listOf("name", "display_name", "description", "source")
            .any { item.string(it)?.contains(query, ignoreCase = true) == true }
        val backends = item.referenceStrings("backends")
        val matchesBackend = when (state.backendFilter) {
            "" -> true
            "_shared" -> backends.isEmpty()
            else -> state.backendFilter in backends
        }
        matches && (!skills || (matchesBackend &&
            (state.scopeFilter.isEmpty() || item.referenceScope() == state.scopeFilter)))
    }
    // A filter can hide other cards, but the open card must keep its in-flight request or draft.
    val visible = entries.filter { it.string("name") == selected || it in filtered }
    val locked = state.hasUnsavedChanges || state.working
    val newEntry = m[if (skills) "referenceNewSkill" else "referenceNewMemory"]
    val icon = if (skills) "graduation-cap" else "brain"
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            LibraryHeader(m[if (skills) "skills" else "memory"], folder) {
                SettingsRefreshButton(m["refresh"], refreshing, enabled = api != null) { state.refresh++ }
                if (folder != null) LibraryButton("folder-open", m["libraryOpenFolder"], enabled = !tab.acting) {
                    tab.action { post("/explorer/reveal", jsonFields("path" to folder)) }
                }
            }
            for (problem in listOfNotNull(state.listError, state.writeError, tab.actionError)) {
                WorkProblemBanner(problem)
            }
            if (state.importedMemoryCount > 0) {
                Muted(m["memoryImport.done"].replace("{count}", state.importedMemoryCount.toString()),
                    modifier = Modifier.padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2))
            }
            Column(Modifier.widthIn(max = 768.dp).fillMaxWidth().padding(StudioTokens.panePad),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space5)) {
                if (catalog == null && state.listError == null) {
                    LibraryCaption(m["loading"])
                    return@Column
                }
                Text(
                    if (skills) m["librarySkillsIntro"].replace("{file}", "SKILL.md")
                    else m["libraryMemoryIntro"].replace("{folder}", folder.orEmpty()),
                    modifier = Modifier.studioArrival(),
                    color = colors.muted,
                )
                if (!skills && catalog != null) ReferenceIndex(catalog, state)
                Column(Modifier.studioArrival(delay = StudioMotion.stagger * (if (skills) 1 else 2)),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LibrarySectionTitle(m[if (skills) "skills" else "libraryMemoryEntries"], Modifier.weight(1f))
                        if (!skills && api != null && catalog != null) {
                            LibraryButton("download", m["memoryImport.button"], enabled = !locked && state.creation == null) {
                                state.importedMemoryCount = 0
                                state.memoryImport = MemoryImportState(api.binding)
                            }
                            Spacer(Modifier.width(StudioTokens.space2))
                        }
                        LibraryButton("plus", newEntry, enabled = api != null && !locked && state.creation == null) {
                            state.editor = null
                            state.selected = null
                            state.creation = ReferenceCreation(requireNotNull(api).binding)
                            state.writeError = null
                        }
                    }
                    state.creation?.let { creation ->
                        LibraryCard {
                            ReferenceCreateForm(creation, skills, state.working, cancel = {
                                state.creation = null
                                state.writeError = null
                            }, submit = {
                                val name = creation.name.text.trim()
                                enqueueReferenceWrite(tab, state, creation.binding, editing = false) {
                                    if (skills) {
                                        post(collection, buildJsonObject {
                                            put("name", name)
                                            put("description", creation.description.text)
                                        })
                                    } else {
                                        put("$collection/"+segment(name), buildJsonObject {
                                            put("create", true)
                                            put("description", creation.description.text)
                                            put("content", creation.content.text)
                                        })
                                    }
                                    state.creation = null
                                    state.select(name)
                                }
                            })
                        }
                    }
                    if (entries.isNotEmpty()) {
                        LibrarySearchField(state.search, { state.search = it }, m[if (skills) "librarySkillsSearch" else "libraryMemorySearch"])
                        if (skills) {
                            SkillsFilters(state, entries)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LibraryCaption(m["librarySkillResults"].replace("{count}", filtered.size.toString()), Modifier.weight(1f))
                                if (query.isNotEmpty() || state.scopeFilter.isNotEmpty() || state.backendFilter.isNotEmpty()) {
                                    WorkAction(m["librarySkillReset"], null, outlined = false) {
                                        state.search = TextFieldValue()
                                        state.scopeFilter = ""
                                        state.backendFilter = ""
                                    }
                                }
                            }
                        }
                    }
                    if (state.hasUnsavedChanges) LibraryCaption(m["referenceWaitingForSave"])
                    if (visible.isEmpty() && state.listError == null && state.creation == null) {
                        if (entries.isEmpty()) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 170.dp)) {
                                LibraryEmpty(icon, m[if (skills) "libraryNoSkills" else "libraryNoMemory"],
                                    m[if (skills) "libraryNoSkillsHint" else "libraryNoMemoryHint"].replace("{folder}", folder.orEmpty()))
                            }
                        } else {
                            Text(m[if (skills) "libraryNoMatchingSkills" else "libraryNoMatchingMemory"], color = colors.muted)
                        }
                    }
                    for (item in visible) {
                        val name = requireNotNull(item.string("name"))
                        val open = name == selected
                        val detail = state.detail?.takeIf { open && it.name == name && it.binding == api?.binding }
                        val editor = state.editor?.takeIf { it.original.name == name }
                        val readOnly = skills && (item.flag("readonly") || item.string("source") != null)
                        key(name) {
                            LibraryCard(selected = open, padding = 0.dp, gap = 0.dp) {
                                ReferenceSummary(item, skills, readOnly, open, locked) { state.select(name) }
                                if (open) {
                                    Rule()
                                    Column(Modifier.fillMaxWidth().padding(StudioTokens.space3), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                                        when {
                                            editor != null -> {
                                                ReferenceEditForm(editor, skills, state.working, cancel = {
                                                    state.editor = null
                                                    state.selected = null
                                                    state.writeError = null
                                                }, save = {
                                                    enqueueReferenceWrite(tab, state, editor.original.binding, editing = true) {
                                                        val updated = put("$collection/"+segment(editor.original.name), buildJsonObject {
                                                            put("expected_updated_at", editor.revision)
                                                            if (!skills) put("description", editor.description.text)
                                                            put("content", editor.content.text)
                                                        })
                                                        state.detail = ReferenceDetail(editor.original.name, updated.jsonObject, binding)
                                                        state.editor = null
                                                        state.selected = null
                                                    }
                                                })
                                                state.detailError?.let { ProblemText(it) }
                                            }
                                            detail != null -> {
                                                val revision = detail.data.string("updated_at")
                                                if (!readOnly && revision != null) {
                                                    SideEffect { if (state.editor == null) state.editor = ReferenceEditor(detail, revision) }
                                                }
                                                ReferenceView(detail, skills, readOnly) { state.selected = null }
                                            }
                                            state.detailError != null -> {
                                                ProblemText(requireNotNull(state.detailError))
                                                WorkAction(m["close"], null, onClick = { state.selected = null })
                                            }
                                            else -> LibraryCaption(m["loading"])
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                ReferenceIssues(catalog, StudioMotion.stagger * (if (skills) 2 else 3))
            }
        }
        VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        val importState = state.memoryImport
        if (importState != null && api != null && importState.binding == api.binding) {
            MemoryImportDialog(tab, api, importState, folder.orEmpty(), close = { state.memoryImport = null }) { result ->
                state.catalog = result.obj("memory") ?: state.catalog
                state.importedMemoryCount += result.memoryStrings("imported").size
                state.refresh++
            }
        }
    }
}

@Composable
private fun SkillsFilters(state: ReferencePageState, entries: List<JsonObject>) {
    val m = localMessages.current
    val colors = localColors.current
    val scopes = listOf("" to m["libraryFilterAll"], "studio" to m["referenceScopeStudio"],
        "global" to m["referenceScopeGlobal"]) + if (entries.any { it.referenceScope() == "project" }) {
        listOf("project" to m["referenceScopeProject"])
    } else emptyList()
    val backends = listOf("" to m["libraryFilterAll"], "_shared" to m["referenceShared"]) +
        entries.flatMap { it.referenceStrings("backends") }.distinct().sorted().map { it to it }
    Column(Modifier.fillMaxWidth().background(colors.inset, RoundedCornerShape(StudioTokens.radiusLg)).padding(StudioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        SkillsFilterRow(m["libraryFilterBackend"], state.backendFilter, backends) { state.backendFilter = it }
        SkillsFilterRow(m["libraryFilterScope"], state.scopeFilter, scopes) { state.scopeFilter = it }
    }
}

@Composable
private fun SkillsFilterRow(label: String, selected: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    val colors = localColors.current
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Text(label, fontSize = StudioTokens.textSmall, fontWeight = FontWeight.Medium, color = colors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            for ((value, title) in options) {
                StudioChip(title, value == selected, { change(value) })
            }
        }
    }
}

@Composable
private fun ReferenceSummary(item: JsonObject, skills: Boolean, readOnly: Boolean, open: Boolean, locked: Boolean,
                             select: () -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val skillIcon = if (skills) "graduation-cap" else "brain"
    val iconTint = colors.navigationTint(if (skills) "skills" else "memory")
    Row(Modifier.fillMaxWidth().padding(StudioTokens.space3), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
        StudioIcon(skillIcon, Modifier.padding(top = StudioTokens.spaceHair).size(StudioTokens.iconSize), iconTint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
            Text(item.referenceTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium, fontFamily = if (skills) null else localStudioMono.current)
            if (skills) item.string("source")?.let { source ->
                val scope = when (item.string("scope")) {
                    "project" -> m["librarySkillScopeProject"]
                    "global" -> m["librarySkillScopeGlobal"]
                    else -> m["librarySkillScopeBuiltin"]
                }
                LibraryCaption(listOf(scope, source, item.referenceStrings("backends").joinToString(", "))
                    .filter(String::isNotBlank).joinToString(" · "))
            }
            item.string("description")?.takeIf(String::isNotBlank)?.let {
                Text(it, color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            item.string("warning")?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    StudioIcon("triangle-alert", Modifier.size(StudioTokens.iconSize), colors.warning)
                    Text(it, color = colors.warning, fontSize = StudioTokens.textSmall)
                }
            }
            val meta = if (skills) {
                val files = item.string("file_count")?.toIntOrNull() ?: item.referenceStrings("files").size
                val fileLabel = if (files > 0) m["librarySkillFileCount"].replace("{amount}", files.toString()) else "SKILL.md"
                val users = item.objects("used_by").mapNotNull { it.string("name") }
                val availability = when {
                    item.string("name") == "voxarium_studio:producer" -> m["libraryProducerAutomatic"]
                    item.flag("automatic") -> m["librarySkillsAutomatic"]
                    users.isNotEmpty() -> listOf(m["referenceUsedBy"], users.joinToString(", ")).joinToString(" ")
                    else -> m["librarySkillUnassigned"]
                }
                listOf(fileLabel, availability)
            } else {
                listOfNotNull(libraryTime(item.string("updated_at"), m.language),
                    item.obj("author_member")?.string("name") ?: item.string("author") ?: m["libraryMemoryAuthor"],
                    item.string("chars")?.takeUnless { it == "0" }?.let { m["libraryMemoryChars"].replace("{count}", it) })
            }
            LibraryCaption(meta.joinToString(" · "))
        }
        LibraryButton(if (readOnly) "file-text" else "pencil", m[if (readOnly) "libraryReadSkill" else "referenceEdit"],
            enabled = !open && !locked, onClick = select)
    }
}

@Composable
private fun ReferenceView(detail: ReferenceDetail, skills: Boolean, readOnly: Boolean, close: () -> Unit) {
    val m = localMessages.current
    val data = detail.data
    LibraryCaption(referencePath(data, skills), mono = true)
    Box(Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 330.dp)) {
        LibraryCodePreview(data.string("content").orEmpty(), withNumbers = false)
    }
    if (readOnly) LibraryCaption(m["referenceReadOnly"])
    WorkAction(m["close"], null, onClick = close)
}

@Composable
private fun ReferenceIssues(catalog: JsonObject?, delay: Int) {
    val m = localMessages.current
    val colors = localColors.current
    for ((key, label) in listOf("invalid" to "referenceInvalid", "missing" to "referenceMissing")) {
        val problems = catalog?.objects(key).orEmpty()
        if (problems.isEmpty()) continue
        key(key) {
            Column(Modifier.studioArrival(delay = delay), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                LibrarySectionTitle(m[label])
                problems.forEach { problem ->
                    val surface = colors.warningSurface
                    Row(Modifier.fillMaxWidth().background(surface, RoundedCornerShape(StudioTokens.radiusMd)).padding(StudioTokens.space2),
                        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        StudioIcon("triangle-alert", Modifier.size(StudioTokens.iconSize), StudioTokens.ensureContrast(colors.warning, surface))
                        Column {
                            Text(problem.string("name") ?: problem.string("file").orEmpty(), fontSize = StudioTokens.textSmall,
                                fontFamily = localStudioMono.current)
                            val detail = problem.string("error")
                                ?: problem.objects("used_by").mapNotNull { it.string("name") }.joinToString(", ")
                            if (detail.isNotBlank()) LibraryCaption(detail, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceIndex(catalog: JsonObject, state: ReferencePageState) {
    val m = localMessages.current
    val colors = localColors.current
    val enabled = catalog.flag("enabled")
    val index = catalog.obj("index")
    val chars = index?.string("chars")?.toIntOrNull() ?: 0
    val cap = catalog.string("index_chars")?.toIntOrNull() ?: 0
    val ratio = if (cap > 0) (chars.toFloat() / cap).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(ratio, tween(if (LocalStudioReducedMotion.current) 0 else StudioMotion.slow,
        easing = StudioMotion.standard))
    Column(Modifier.studioArrival(delay = StudioMotion.stagger), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        LibrarySectionTitle(m["libraryMemorySharing"])
        LibraryCard {
            Column {
                LibrarySectionTitle(m["libraryMemoryEnabled"])
                LibraryCaption(m[if (enabled) "libraryMemoryEnabledHint" else "libraryMemoryDisabledHint"], maxLines = 4)
            }
            if (enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                    Text(m["libraryMemoryIndexCap"], color = colors.muted)
                    WorkPill(cap.toString(), quiet = false)
                }
                Box(Modifier.fillMaxWidth().height(StudioTokens.space2).clip(RoundedCornerShape(StudioTokens.radiusPill)).background(colors.inset)) {
                    Spacer(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.accent))
                }
                LibraryCaption(m["libraryMemoryUsage"].replace("{chars}", chars.toString()).replace("{cap}", cap.toString()))
                StudioSelectionRow(state.showingIndex, { state.showingIndex = !state.showingIndex },
                    Modifier.fillMaxWidth(), role = Role.Button) {
                    LibraryChevron(state.showingIndex)
                    LibraryCaption(m["libraryMemoryIndexPreview"])
                }
                StudioDisclosure(state.showingIndex) {
                    val content = index?.string("text").orEmpty()
                    if (content.isBlank()) LibraryCaption(m["libraryMemoryIndexEmpty"], maxLines = 3)
                    else Box(Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 256.dp).background(colors.inset, RoundedCornerShape(StudioTokens.radiusMd))) {
                        LibraryCodePreview(content, withNumbers = false)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
private val referenceFieldLabelWidth = 96.dp

@Composable
private fun ReferenceCreateForm(creation: ReferenceCreation, skills: Boolean, working: Boolean, cancel: () -> Unit, submit: () -> Unit) {
    val m = localMessages.current
    LibrarySectionTitle(m[if (skills) "referenceNewSkill" else "referenceNewMemory"])
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3), verticalAlignment = Alignment.CenterVertically) {
        Text(m["referenceName"], Modifier.width(referenceFieldLabelWidth), color = localColors.current.muted)
        ReferenceField(m["referenceName"], creation.name, { creation.name = it }, !working, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
        Text(m["referenceDescription"], Modifier.width(referenceFieldLabelWidth).padding(top = StudioTokens.space1), color = localColors.current.muted)
        TextArea(creation.description, { creation.description = it }, Modifier.weight(1f).height(56.dp)
            .semantics { contentDescription = m["referenceDescription"] }, enabled = !working)
    }
    if (!skills) {
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
            Text(m["referenceContent"], Modifier.width(referenceFieldLabelWidth).padding(top = StudioTokens.space1), color = localColors.current.muted)
            TextArea(creation.content, { creation.content = it }, Modifier.weight(1f).height(120.dp)
                .semantics { contentDescription = m["referenceContent"] }, enabled = !working)
        }
    }
    Row(Modifier.padding(start = referenceFieldLabelWidth + StudioTokens.space3), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        WorkAction(m["referenceCreate"], null, primary = true, enabled = !working && creation.name.text.isNotBlank() &&
            (skills || creation.description.text.isNotBlank()), onClick = submit)
        WorkAction(m["cancel"], null, onClick = cancel, enabled = !working)
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ReferenceEditForm(editor: ReferenceEditor, skills: Boolean, working: Boolean, cancel: () -> Unit, save: () -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val data = editor.original.data
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        if (skills) StudioIcon("file-text", Modifier.size(StudioTokens.iconSize), colors.faint)
        LibraryCaption(referencePath(data, skills), Modifier.weight(1f), mono = true)
        if (editor.dirty) Text(m["libraryDocDirty"], color = colors.warning, fontSize = StudioTokens.textSmall)
    }
    if (!skills) {
        LibraryCaption(m["referenceDescription"])
        TextArea(editor.description, { editor.description = it }, Modifier.fillMaxWidth().height(56.dp)
            .semantics { contentDescription = m["referenceDescription"] }, enabled = !working)
        LibraryCaption(m["referenceContent"])
    }
    val label = if (skills) "SKILL.md" else m["referenceContent"]
    TextArea(editor.content, { editor.content = it }, Modifier.fillMaxWidth().height(if (skills) 324.dp else 180.dp)
        .semantics { contentDescription = label }, enabled = !working)
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        WorkAction(m["saveDocument"], null, primary = true,
            enabled = !working && editor.dirty && (skills || editor.description.text.isNotBlank()), onClick = save)
        WorkAction(m["libraryRevert"], null, enabled = editor.dirty && !working) {
            editor.description = TextFieldValue(data.string("description").orEmpty())
            editor.content = TextFieldValue(data.string("content").orEmpty())
        }
        WorkAction(m[if (editor.dirty) "discardDocumentDraft" else "close"], null, enabled = !working, onClick = cancel)
    }
}

private fun referencePath(data: JsonObject, skills: Boolean): String {
    val path = data.string("path") ?: return if (skills) "SKILL.md" else data.string("name").orEmpty()
    return if (skills && !path.endsWith("SKILL.md")) "$path/SKILL.md" else path
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ReferenceField(label: String, value: TextFieldValue, change: (TextFieldValue) -> Unit, enabled: Boolean, modifier: Modifier) {
    StudioField(label, value, change, modifier, enabled = enabled)
}

