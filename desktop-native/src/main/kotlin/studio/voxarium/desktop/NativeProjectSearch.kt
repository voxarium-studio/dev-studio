package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.jewel.ui.component.Text

// §6.1: each provider owns its hits and deep answer; opening a hit keeps the selected project.
internal fun searchHitPage(path: String?, dataDirectory: String?): Page? {
    val target = path?.replace('\\', '/')?.takeIf(String::isNotBlank) ?: return null
    val dataName = dataDirectory?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank) ?: ".voxarium_studio"
    val docsPrefix = "$dataName/docs/"
    return if (target.startsWith(docsPrefix)) Page(Section.DOCUMENTS.route, Selectors(path = target.removePrefix(docsPrefix)))
    else Page(Section.EXPLORER.route, Selectors(path = target))
}

@Composable
internal fun NativeProjectSearch(tab: ProjectTab, close: () -> Unit, navigate: (Page) -> Unit) {
    val api = tab.api ?: return
    val m = localMessages.current
    val words = remember(m.language) { PluginWords(m.language, "search") }
    val colors = localColors.current
    val focus = remember { FocusRequester() }
    var input by remember { mutableStateOf(TextFieldValue()) }
    var groups by remember { mutableStateOf(emptyList<JsonObject>()) }
    var request by remember { mutableIntStateOf(0) }
    var deep by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Problem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(-1) }
    val text = input.text.trim()
    val dataDir = tab.chromeProject?.string("data_dir")
    val paths = groups.flatMap { it.objects("results") }.map { searchHitPage(it.string("path"), dataDir) }
    val selectable = paths.indices.filter { paths[it] != null }

    fun submit() {
        val destination = paths.getOrNull(selected)
        if (destination != null) navigate(destination)
        else if (text.isNotEmpty()) {
            deep = true
            request++
        }
    }

    LaunchedEffect(api, text, request) {
        if (text.isEmpty()) return@LaunchedEffect
        if (!deep) delay(180)
        val searchingDeep = deep
        busy = true
        try {
            val endpoint = "/search" + query("q" to text, "deep" to searchingDeep.toString())
            groups = api.get(endpoint, Duration.ofSeconds(if (searchingDeep) 105 else 20)).jsonObject.objects("providers")
            if (selected !in groups.flatMap { it.objects("results") }.indices) selected = -1
            problem = null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            problem = error.problem()
        } finally {
            busy = false
        }
    }

    DialogWindow(onCloseRequest = close, title = words["title"], state = rememberDialogState(size = DpSize(720.dp, 560.dp)),
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Escape -> { close(); true }
                Key.Enter -> { submit(); true }
                Key.DirectionDown, Key.DirectionUp -> {
                    val current = selectable.indexOf(selected)
                    val next = if (event.key == Key.DirectionDown) current + 1
                        else if (current == -1) selectable.lastIndex else current - 1
                    selected = selectable.getOrNull(next) ?: -1
                    true
                }
                else -> false
            }
        }) {
        Column(Modifier.fillMaxSize().padding(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                StudioField(words["title"], input, { value ->
                    input = value
                    if (value.text.trim() != text) {
                        selected = -1
                        deep = false
                        problem = null
                        groups = emptyList()
                    }
                }, Modifier.weight(1f).focusRequester(focus), placeholder = words["placeholder"])
                WorkAction(words["deep"], "search", enabled = text.isNotEmpty() && !busy) { deep = true; request++ }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
            if (busy) Muted(m["loading"])
            problem?.let { ProblemText(it) }
            if (!busy && groups.isEmpty() && text.isNotEmpty() && problem == null) Muted(words["noResults"])
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                var index = 0
                for (group in groups) {
                    LibraryCard {
                        LibrarySectionTitle(group.string("label").orEmpty())
                        group.string("note")?.let { Muted(it) }
                        group.string("error")?.let { Text(it, color = colors.danger) }
                        val hits = group.objects("results")
                        if (hits.isEmpty() && group.string("error") == null) Muted(words["noResults"])
                        for (hit in hits) {
                            val row = index++
                            val path = hit.string("path")
                            val pick = paths.getOrNull(row)
                            val location = listOfNotNull(path, hit.string("line")).joinToString(":")
                            val description = listOfNotNull(hit.string("kind"), hit.string("subtitle"),
                                location.takeIf(String::isNotEmpty)).joinToString(" · ")
                            val title = hit.string("title") ?: path.orEmpty()
                            if (pick == null) {
                                Text(title)
                                Muted(description)
                            } else {
                                val bring = remember(row) { BringIntoViewRequester() }
                                LaunchedEffect(row, selected) { if (row == selected) bring.bringIntoView() }
                                Box(Modifier.bringIntoViewRequester(bring)) {
                                    ListRow(title, description, row == selected) { navigate(pick) }
                                }
                            }
                            hit.string("snippet")?.takeIf(String::isNotBlank)?.let { Muted(it) }
                        }
                        group.string("answer")?.takeIf(String::isNotBlank)?.let { answer ->
                            LibrarySectionTitle(words["answer"])
                            SelectionContainer { Text(answer) }
                        }
                    }
                }
            }
        }
    }
}
