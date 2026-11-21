package studio.voxarium.desktop

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

internal enum class Section(val label: String, val route: String, private vararg val topics: String) {
    EXPLORER("explorer", "/explorer", "explorer.file_changed"),
    DOCUMENTS("documents", "/documents", "team.doc."),
    DOCUMENT_GRAPH("documentGraph", "/documents/graph", "team.doc.", "sync.", "explorer.file_changed"),
    TASKS("tasks", "/tasks", "team.task.", "team.member.message", "team.updated"),
    CHATS("chats", "/chats", "team.chat.", "team.updated"),
    PRODUCER("producer", "/producer", "team.chat.", "team.task.", "team.updated"),
    PIPELINES("pipelines", "/pipelines", "run.", "node.status_changed", "explorer.file_changed"),
    AUTOMATIONS("automations", "/automations", "automation.", "explorer.file_changed", "plugin."),
    TEAMS("teams", "/teams", "team.updated", "team.task."),
    STATISTICS("statistics", "/statistics", "team.updated", "team.task."),
    VCS("versionControl", "/vcs", "explorer.file_changed"),
    MEMORY("memory", "/memory", "team.memory.", "explorer.file_changed"),
    SKILLS("skills", "/skills", "team.updated", "explorer.file_changed"),
    MCP("mcp", "/mcp", "explorer.file_changed"),
    PLUGINS("plugins", "/plugins", "plugin."),
    PROJECT_SETTINGS("projectSettings", "/project-settings", "project.", "team.updated", "remote.binding_change", "sync.status"),
    REMOTE_AI("remote", "/remote-ai"),
    TOOLS("tools", "/tools");

    fun relevant(type: String) = topics.any { type.startsWith(it) }

    val usesWorkspaceList: Boolean
        get() = when (this) {
            DOCUMENT_GRAPH, AUTOMATIONS, STATISTICS, VCS, MEMORY, SKILLS, MCP, PROJECT_SETTINGS, REMOTE_AI -> false
            else -> true
        }

    companion object {
        fun from(page: Page) = entries.firstOrNull { it.route == page.path || it == PIPELINES && page.path.startsWith("/pipelines/") }
            ?: TOOLS
    }
}

internal data class WorkspaceItem(
    val id: String,
    val title: String,
    val caption: String? = null,
    val status: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
)

internal data class WorkspaceList(
    val items: List<WorkspaceItem>,
    val directory: String = "",
    val people: List<JsonObject> = emptyList(),
    val teams: List<JsonObject> = emptyList(),
    val activeTasks: List<JsonObject> = emptyList(),
)

internal data class WorkspaceDetail(val item: WorkspaceItem, val data: JsonObject, val runs: List<JsonObject> = emptyList())

internal fun Page.select(section: Section, item: WorkspaceItem): Page = when (section) {
    Section.EXPLORER, Section.DOCUMENTS, Section.DOCUMENT_GRAPH -> Page(section.route, Selectors(path = item.id))
    Section.TASKS -> Page(section.route, Selectors(task = item.id))
    Section.CHATS, Section.PRODUCER -> Page(section.route, Selectors(chat = item.id))
    Section.PIPELINES -> Page("${section.route}/${segment(item.id)}")
    Section.TEAMS, Section.PLUGINS -> Page(section.route, Selectors(path = item.id))
    Section.AUTOMATIONS, Section.STATISTICS, Section.VCS, Section.MEMORY, Section.SKILLS,
    Section.MCP, Section.PROJECT_SETTINGS, Section.REMOTE_AI -> Page(section.route)
    Section.TOOLS -> Page(item.id)
}

internal fun Page.selection(section: Section): String? = when (section) {
    Section.CHATS, Section.PRODUCER -> query.chat
    Section.TASKS -> query.task
    Section.PIPELINES -> path.removePrefix("/pipelines/").takeIf { it != path }
        ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
    else -> query.path
}

internal fun Page.browserTarget(): Pair<String, String> {
    val search = listOfNotNull(
        query.path?.let { "path" to it },
        query.task?.let { "task" to it },
        query.chat?.let { "chat" to it },
        query.assign?.let { "assign" to it },
    )
    val route = if (path == Section.TOOLS.route) "/project-settings" else path
    return route to if (search.isEmpty()) "" else query(*search.toTypedArray())
}

private fun JsonObject.id() = requireNotNull(string("id"))
private fun JsonObject.item(title: String, caption: String? = null, id: String = id(), status: String? = null) =
    WorkspaceItem(id, title, caption, status, this)

internal suspend fun ProjectApi.load(section: Section, page: Page): WorkspaceList = when (section) {
    Section.EXPLORER -> {
        val selected = page.query.path.orEmpty()
        val tree = try {
            get("/explorer/tree" + query("path" to selected)).jsonObject
        } catch (error: ApiFailure) {
            if (error.status != 404 || selected.isEmpty()) throw error
            get("/explorer/tree" + query("path" to selected.substringBeforeLast('/', ""))).jsonObject
        }
        WorkspaceList(tree.objects("entries").map { file ->
            val path = requireNotNull(file.string("path"))
            file.item(file.string("name") ?: path, id = path, status = file.string("kind"))
        }.sortedWith(compareBy({ !it.payload.flag("is_dir") }, { it.title.lowercase() })), tree.string("path").orEmpty())
    }
    Section.DOCUMENTS -> WorkspaceList(get("/docs").objects().map { doc ->
        val path = requireNotNull(doc.string("path"))
        doc.item(doc.string("title")?.takeIf(String::isNotBlank) ?: path, path, path)
    })
    Section.TASKS -> {
        val teams = get("/teams").objects()
        val people = teams.flatMap { it.objects("members") }
        val names = people.associate { it.id() to it.string("name") }
        val tasks = teams.flatMap { team ->
            get("/teams/${segment(team.id())}/tasks").objects().map { task ->
                task.item(
                    task.string("title") ?: task.id(),
                    listOfNotNull(task.string("display_id"), names[task.string("assignee_id")]).joinToString(" · "),
                    status = task.string("status"),
                )
            }
        }
        WorkspaceList(tasks, people = people, teams = teams)
    }
    Section.CHATS, Section.PRODUCER -> coroutineScope {
        val teamCall = async { get("/teams").objects() }
        val chats = get("/chats").objects()
        val teams = teamCall.await()
        val people = teams.flatMap { it.objects("members") }
        val names = people.associate { it.id() to it.string("name") }
        val producerIds = people.filter { it.string("builtin") == "producer" }.mapTo(mutableSetOf()) { it.id() }
        val conversations = if (section == Section.PRODUCER) chats.filter { it.string("member_id") in producerIds } else chats
        val owner = people.find { member -> member.id() == conversations.find { it.id() == page.query.chat }?.string("member_id") }
        val teamId = owner?.string("team_id") ?: teams.firstOrNull()?.id()
        val activeTasks = if (section == Section.PRODUCER && teamId != null) {
            get("/teams/${segment(teamId)}/tasks?archived=false").objects().filter { task ->
                task.string("status") in setOf("pending", "in_progress", "needs_review", "blocked") ||
                    task.string("status") == "todo" && task.flag("producer_approved")
            }.sortedByDescending { it.string("status") == "in_progress" }
        } else emptyList()
        WorkspaceList(conversations.map { chat ->
            val name = names[chat.string("member_id")]
            chat.item(chat.string("title")?.takeIf(String::isNotBlank) ?: name.orEmpty(), name,
                status = if (chat.flag("is_running")) "running" else chat.string("producer_state"))
        }, people = people, teams = teams, activeTasks = activeTasks)
    }
    Section.PIPELINES -> WorkspaceList(get("/pipelines").objects().map { pipeline ->
        pipeline.item(pipeline.string("name") ?: pipeline.id(), pipeline.string("description"))
    })
    Section.TEAMS -> get("/teams").objects().let { teams ->
        WorkspaceList(teams.map { team -> team.item(team.string("name") ?: team.id(), team.string("description")) }, teams = teams)
    }
    Section.PLUGINS -> WorkspaceList(get("/plugins").objects().map { plugin ->
        plugin.item(plugin.string("name") ?: plugin.id(), plugin.string("version"), status = plugin.string("status"))
    })
    Section.TOOLS -> WorkspaceList(get("/plugins/pages").objects().mapNotNull { plugin ->
        val route = plugin.string("route")?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: return@mapNotNull null
        plugin.item(plugin.string("title") ?: route, plugin.string("plugin_id"), route)
    })
    Section.DOCUMENT_GRAPH, Section.AUTOMATIONS, Section.STATISTICS, Section.VCS, Section.MEMORY, Section.SKILLS,
    Section.MCP, Section.PROJECT_SETTINGS, Section.REMOTE_AI -> WorkspaceList(emptyList())
}

internal suspend fun ProjectApi.detail(section: Section, page: Page, list: WorkspaceList?): WorkspaceDetail? {
    val selected = page.selection(section) ?: return null
    val item = list?.items?.find { it.id == selected }
    return when (section) {
        Section.EXPLORER -> {
            if (selected == list?.directory) return null
            val preview = get("/explorer/file" + query("path" to selected)).jsonObject
            WorkspaceDetail(item ?: WorkspaceItem(selected, selected.substringAfterLast('/')), preview)
        }
        Section.DOCUMENTS -> {
        val doc = get(documentEndpoint(selected)).jsonObject
            WorkspaceDetail(item ?: WorkspaceItem(selected, doc.string("title") ?: selected), doc)
        }
        Section.TASKS -> {
            val team = item?.payload?.string("team_id") ?: return null
            WorkspaceDetail(item, get("/teams/${segment(team)}/tasks/${segment(selected)}").jsonObject)
        }
        Section.CHATS, Section.PRODUCER -> {
            val chat = get("/chats/${segment(selected)}").jsonObject
            WorkspaceDetail(item ?: WorkspaceItem(selected, chat.string("title").orEmpty()), chat)
        }
        Section.PIPELINES -> coroutineScope {
            val runs = async { get("/runs" + query("pipeline_id" to selected, "limit" to "20")).objects() }
            val pipeline = get("/pipelines/${segment(selected)}").jsonObject
            WorkspaceDetail(item ?: WorkspaceItem(selected, selected), pipeline, runs.await())
        }
        Section.TEAMS -> item?.let { WorkspaceDetail(it, get("/teams/${segment(selected)}").jsonObject) }
        Section.PLUGINS -> item?.let { WorkspaceDetail(it, it.payload) }
        Section.TOOLS, Section.DOCUMENT_GRAPH, Section.AUTOMATIONS, Section.STATISTICS, Section.VCS, Section.MEMORY,
        Section.SKILLS, Section.MCP, Section.PROJECT_SETTINGS, Section.REMOTE_AI -> null
    }
}
