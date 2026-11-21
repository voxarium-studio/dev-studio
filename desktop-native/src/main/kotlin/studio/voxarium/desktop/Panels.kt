package studio.voxarium.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
internal fun WorkspaceSection(section: Section, tab: ProjectTab, navigate: (Page) -> Unit, workspace: Workspace, modifier: Modifier) {
    when (section) {
        Section.PIPELINES -> PipelinePage(tab, navigate, workspace, modifier)
        Section.TASKS -> TasksPage(tab, navigate, modifier)
        Section.CHATS -> ChatsPage(tab, navigate, modifier)
        Section.PRODUCER -> ProducerPage(tab, navigate, modifier)
        Section.TEAMS -> TeamsPage(tab, navigate, workspace, modifier)
        Section.PLUGINS -> PluginsPage(tab, modifier)
        Section.TOOLS -> PluginPages(tab, navigate, workspace, modifier)
        else -> error("Section ${section.name} has a dedicated workspace renderer")
    }
}

@Composable
private fun PluginPages(tab: ProjectTab, navigate: (Page) -> Unit, workspace: Workspace, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val list = tab.list
    var filter by remember { mutableStateOf(TextFieldValue()) }
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.widthIn(min = 225.dp, max = 330.dp).fillMaxWidth(.36f).fillMaxHeight()
            .background(colors.sidebar).padding(start = StudioTokens.space2, end = StudioTokens.space1, top = StudioTokens.space2)) {
            StudioField(m["filter"], filter, { filter = it },
                Modifier.fillMaxWidth().padding(end = StudioTokens.space1, bottom = StudioTokens.space2),
                placeholder = m["filter"])
            tab.listError?.let { ProblemText(it) }
            val items = list?.items.orEmpty().filter {
                it.title.contains(filter.text, ignoreCase = true) || it.caption?.contains(filter.text, ignoreCase = true) == true
            }
            if (items.isEmpty() && tab.listError == null) {
                Muted(m[when {
                    list == null -> "loading"
                    filter.text.isNotBlank() -> "noMatches"
                    else -> "emptyList"
                }], modifier = Modifier.padding(StudioTokens.space2))
            }
            ScrollList(Modifier.weight(1f)) {
                items(items, key = { it.id }) { item ->
                    ListRow(item.title, item.caption, item.id == tab.page.path) { navigate(tab.page.select(Section.TOOLS, item)) }
                }
            }
        }
        Rule(vertical = true)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            tab.actionError?.let {
                Box(Modifier.fillMaxWidth().padding(StudioTokens.space3)) { ProblemText(it) }
                Rule()
            }
            ToolsPanel(tab, workspace)
        }
    }
}

@Composable
private fun ToolsPanel(tab: ProjectTab, workspace: Workspace) {
    val m = localMessages.current
    fun open(route: String) {
        tab.api?.web(route)?.let { openBrowser(it, workspace) }
    }
    val plugin = tab.list?.items?.firstOrNull { it.id == tab.page.path || it.id == tab.page.query.path }
    val native = plugin?.payload?.let(::nativePluginPage)
    val api = tab.api
    if (native != null && api != null) {
        key(native, api) { NativePluginSettingsPage(native, api, plugin.title) }
    } else if (plugin != null) {
        WorkHeader {
            Heading(plugin.title, Modifier.weight(1f), StudioTokens.titleSize.value.toInt())
            WorkAction(m["openWeb"], "external-link", primary = true) { open(plugin.id) }
        }
    }
}

