package studio.voxarium.desktop

import androidx.compose.runtime.*
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal class WorkspaceWindow(id: Long, tabs: List<ProjectTab> = emptyList(), active: Int? = null, initialGeometry: Geometry? = null) {
    val id = id
    val tabs = mutableStateListOf<ProjectTab>().apply { addAll(tabs) }
    var active by mutableStateOf(tabs.getOrNull(active ?: -1))
    var visible by mutableStateOf(true)
    var picking by mutableStateOf(false)
    var settings by mutableStateOf(false)
    var settingsAnchor by mutableStateOf<String?>(null)
    var remoteAi by mutableStateOf(false)
    var activation by mutableIntStateOf(0)
    var geometry = initialGeometry

    fun add(tab: ProjectTab) {
        tabs.add(tab)
        select(tab)
    }

    fun select(tab: ProjectTab) {
        active = tab
        picking = false
        settings = false
        remoteAi = false
    }

    fun remove(tab: ProjectTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (active == tab) active = tabs.getOrNull(index) ?: tabs.lastOrNull()
        if (tabs.isEmpty()) picking = false
    }
}

internal data class Problem(val key: String, val details: String? = null)

internal fun Exception.problem(default: String = "requestFailed"): Problem {
    if (this is CancellationException) throw this
    return when {
        this is StartupFailure -> Problem(label, details)
        this is AttachmentTooLarge -> Problem("nativeTaskAttachments.tooLarge")
        this is DocumentNeedsBrowser || this is ApiFailure && message == "document_requires_collaboration" ->
            Problem("documentNeedsBrowser")
        else -> Problem(default, message)
    }
}

internal class Workspace(
    private val scope: CoroutineScope,
    val api: StudioApi,
    val backend: Backend,
    val build: BuildIdentity,
    private val exit: () -> Unit,
) {
    val windows = mutableStateListOf<WorkspaceWindow>()
    private val opening = Mutex()
    private val saving = Mutex()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var serial = 0L
    private var active: WorkspaceWindow? = null
    private var persist = false
    var booting by mutableStateOf(true)
        private set
    var ready by mutableStateOf(false)
        private set
    var quitting by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Problem?>(null)
    var confirmation by mutableStateOf<Confirmation?>(null)
        private set
    var recent by mutableStateOf(emptyList<JsonObject>())
        private set
    var preferences by mutableStateOf(JsonObject(emptyMap()))
        private set
    val agentRuntimeSettings = AgentRuntimeSettingsState(api, scope)
    val releaseUpdates = NativeUpdateState(scope, build.installerVersion)
    var producerAttention by mutableStateOf(emptyList<NativeProducerAttention>())
        private set
    val chatPanelPreference = ChatPanelPreference(scope, studioHome().resolve("kotlin-desktop/chat-panel.json"))
    val chromeConnections get() = agentRuntimeSettings.connections
    var preferenceBusy by mutableStateOf(false)
        private set

    data class Confirmation(val key: String, val action: String, val details: String?, val accept: () -> Unit)

    init {
        scope.launch {
            for (ignored in changed) {
                delay(350)
                val result = changed.tryReceive()
                if (result.isClosed) break
                try {
                    save()
                } catch (error: Exception) {
                    problem = error.problem("sessionSaveFailed")
                }
            }
        }
    }

    suspend fun boot(project: Path?) {
        try {
            chatPanelPreference.load()
            val health = backend.start(project)
            preferences = api.request("/api/settings/application").jsonObject
            // A second shell attached to an Electron-owned server has a different window registry.
            // It can read the session for migration, but only one shell should publish topology.
            var saved: Session? = null
            try {
                saved = api.loadSession()
                persist = backend.owned || health.desktopInstance == null
            } catch (error: Exception) {
                problem = error.problem("sessionLoadFailed")
            }
            saved?.windows?.forEach { savedWindow ->
                val tabs = savedWindow.tabs.map { ProjectTab(scope, api, it.root, it.page) }
                windows.add(WorkspaceWindow(++serial, tabs, savedWindow.activeTab, savedWindow.geometry))
            }
            val main = windows.getOrNull(saved?.activeWindow ?: -1) ?: windows.firstOrNull() ?: newWindow()
            active = main
            ready = true
            agentRuntimeSettings.refresh()
            if (project != null) open(main, project.toString())
            refreshRecent()
            for (window in windows.toList()) {
                window.tabs.toList().forEach { tab -> opening.withLock { if (tab.binding == null) tab.connect() } }
            }
        } catch (error: Exception) {
            problem = error.problem("startFailed")
            if (windows.isEmpty()) newWindow()
        } finally {
            booting = false
            active?.let { focus(it) }
        }
    }

    fun newWindow(): WorkspaceWindow = WorkspaceWindow(++serial).also { windows.add(it) }

    fun focus(window: WorkspaceWindow, raise: Boolean = true) {
        val changed = active !== window || !window.visible
        active = window
        window.visible = true
        if (raise) window.activation++
        if (changed) scheduleSave()
    }

    fun show() = focus(active?.takeIf { it in windows } ?: windows.lastOrNull() ?: newWindow())

    fun handoff(path: String) {
        val window = active?.takeIf { it in windows } ?: windows.lastOrNull() ?: newWindow()
        focus(window)
        if (path.isNotEmpty() && ready) open(window, path)
    }

    fun open(window: WorkspaceWindow?, root: String, create: Boolean = false, attention: NativeProducerAttention? = null) {
        if (quitting) return
        scope.launch {
            try {
                opening.withLock {
                    if (attention != null) {
                        val expected = Path.of(root)
                        val actual = withContext(Dispatchers.IO) { runCatching { expected.toRealPath() }.getOrNull() }
                        if (actual != expected) throw StartupFailure("producerAttention.unavailable")
                    }
                    val binding = api.open(root, create)
                    if (attention != null && !attention.matches(binding)) throw StartupFailure("producerAttention.unavailable")
                    val existing = windows.firstNotNullOfOrNull { candidate ->
                        candidate.tabs.firstOrNull { it.root == binding.root }?.let { candidate to it }
                    }
                    if (existing != null) {
                        val (owner, tab) = existing
                        tab.bind(binding)
                        attention?.let { tab.navigate(it.page) }
                        owner.select(tab)
                        focus(owner)
                    } else {
                        val owner = window?.takeIf { it in windows } ?: active?.takeIf { it in windows } ?: newWindow()
                        val tab = ProjectTab(scope, api, binding.root, attention?.page ?: Page("/explorer"))
                        tab.bind(binding)
                        owner.add(tab)
                        focus(owner)
                    }
                    scheduleSave()
                }
                refreshRecent()
            } catch (error: Exception) {
                if (error is ApiFailure && error.status == 409 && !create && attention == null && window in windows) {
                    confirmation = Confirmation("initializeProject", "initialize", root) { open(window, root, create = true) }
                } else {
                    problem = error.problem()
                    if (attention != null && windows.isEmpty()) show()
                }
            }
        }
    }

    suspend fun observeProducerAttention() {
        while (currentCoroutineContext().isActive) {
            val stream = NativeAttentionStream()
            try {
                api.desktopFrames().collect { frame -> stream.receive(frame)?.let { producerAttention = it } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
            producerAttention = emptyList()
            delay(1500)
        }
    }

    fun activateProducerAttention(attention: NativeProducerAttention) {
        if (!ready || booting || attention !in producerAttention) return
        open(active, attention.root, attention = attention)
    }

    fun refreshRecent() {
        scope.launch {
            try {
                recent = api.request("/api/project/recent").objects()
            } catch (error: Exception) {
                problem = error.problem()
            }
        }
    }

    fun forgetRecent(root: String) {
        scope.launch {
            try {
                recent = api.request("/api/project/recent" + query("path" to root), "DELETE").objects()
            } catch (error: Exception) {
                problem = error.problem()
            }
        }
    }

    suspend fun changelog(): String = api.request("/api/changelog").jsonObject.string("text").orEmpty()

    fun select(window: WorkspaceWindow, tab: ProjectTab) {
        window.select(tab)
        scheduleSave()
    }

    fun move(from: WorkspaceWindow, to: WorkspaceWindow, tab: ProjectTab, before: ProjectTab? = null) {
        if (from !in windows || to !in windows || tab !in from.tabs || quitting || confirmation != null ||
            before === tab || before != null && before !in to.tabs) return
        if (from === to) {
            from.tabs.remove(tab)
            from.tabs.add(before?.let(from.tabs::indexOf) ?: from.tabs.size, tab)
        } else {
            from.remove(tab)
            to.tabs.add(before?.let(to.tabs::indexOf) ?: to.tabs.size, tab)
            to.select(tab)
            focus(to)
        }
        scheduleSave()
    }

    fun closeTab(window: WorkspaceWindow, tab: ProjectTab) = confirmDrafts(listOf(tab)) {
        window.remove(tab)
        tab.dispose()
        scheduleSave()
    }

    fun closeWindow(window: WorkspaceWindow) = confirmDrafts(window.tabs) {
        windows.remove(window)
        window.tabs.forEach(ProjectTab::dispose)
        if (active == window) active = windows.lastOrNull()
        scheduleSave()
    }

    private fun confirmDrafts(tabs: List<ProjectTab>, close: () -> Unit) {
        if (quitting) return
        if (tabs.any(ProjectTab::hasUnsavedChanges)) {
            confirmation = Confirmation("discardDraft", "close", null, close)
        } else close()
    }

    fun dismissConfirmation() { confirmation = null }

    fun confirm() {
        val action = confirmation?.accept
        confirmation = null
        action?.invoke()
    }

    fun applyPreference(body: JsonObject) {
        if (preferenceBusy) return
        preferenceBusy = true
        scope.launch {
            try {
                api.request("/api/settings/application", "PUT", body)
                preferences = api.request("/api/settings/application").jsonObject
            } catch (error: Exception) {
                problem = error.problem()
            } finally {
                preferenceBusy = false
            }
        }
    }

    fun scheduleSave() {
        if (persist && !booting && !quitting) changed.trySend(Unit)
    }

    private suspend fun save() {
        if (!persist) return
        saving.withLock {
            val snapshot = windows.map { window ->
                SavedWindow(window.tabs.map { SavedTab(it.root, it.page) }, window.tabs.indexOf(window.active).takeIf { it >= 0 }, window.geometry)
            }
            api.saveSession(Session(1, snapshot, windows.indexOf(active).takeIf { it >= 0 }))
        }
    }

    fun requestQuit() = requestShutdown(null)

    fun requestUpdateInstall(installer: DownloadedInstaller) {
        if (releaseUpdates.downloaded !== installer || releaseUpdates.busy) return
        requestShutdown(installer)
    }

    private fun requestShutdown(installer: DownloadedInstaller?) {
        if (quitting || confirmation != null) return
        quitting = true
        scope.launch {
            val activity = if (ready && backend.owned) {
                try {
                    api.request("/api/projects/activity").jsonObject
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
            } else null
            val drafts = windows.any { window -> window.tabs.any(ProjectTab::hasUnsavedChanges) }
            val activeWork = ready && backend.owned && activity?.flag("requires_confirmation") != false
            if (drafts || activeWork) {
                show()
                val names = activity?.objects("projects")?.filter { it.string("status") != "idle" }
                    ?.mapNotNull { it.string("name") }
                val message = if (installer == null) {
                    if (activeWork && drafts) "quitWorkAndDraft" else if (activeWork) "quitWork" else "discardDraft"
                } else {
                    if (activeWork && drafts) "nativeUpdate.installWorkAndDraft"
                    else if (activeWork) "nativeUpdate.installWork"
                    else "nativeUpdate.installDiscardDraft"
                }
                confirmation = Confirmation(message, if (installer == null) "quit" else "nativeUpdate.install", names?.joinToString("\n")) {
                    finishQuit(installer)
                }
                quitting = false
            } else finishQuit(installer)
        }
    }

    private fun finishQuit(installer: DownloadedInstaller?) {
        quitting = true
        scope.launch {
            try {
                save()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                problem = error.problem("sessionSaveFailed")
                if (installer == null) confirmation = Confirmation("quitWithoutSave", "quit", error.message) { stopAndExit() }
                show()
                quitting = false
                return@launch
            }
            stopAndExit(installer)
        }
    }

    private fun stopAndExit(installer: DownloadedInstaller? = null) {
        quitting = true
        scope.launch {
            val handoff = if (installer == null) null else try {
                prepareNativeUpdate(installer)
            } catch (error: Exception) {
                releaseUpdates.installFailed(error)
                show()
                quitting = false
                return@launch
            }
            try {
                backend.stop()
            } catch (error: Exception) {
                handoff?.cancel()
                problem = error.problem("stopFailed")
                show()
                quitting = false
                return@launch
            }
            try {
                handoff?.approve()
                exit()
            } catch (error: Exception) {
                handoff?.cancel()
                releaseUpdates.installFailed(error)
                show()
                quitting = false
            }
        }
    }
}

internal class ProjectTab(
    parent: CoroutineScope,
    private val studio: StudioApi,
    val root: String,
    initialPage: Page = Page("/explorer"),
) {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    var binding by mutableStateOf<ProjectBinding?>(null)
        private set
    var api by mutableStateOf<ProjectApi?>(null)
        private set
    var connectionProblem by mutableStateOf<Problem?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set
    var eventsConnected by mutableStateOf(false)
        private set
    var chromeProject by mutableStateOf<JsonObject?>(null)
        private set
    var chromeCounts by mutableStateOf<JsonObject?>(null)
        private set
    var chromeStatus by mutableStateOf<JsonObject?>(null)
        private set
    var chromePluginPages by mutableStateOf<List<JsonObject>>(emptyList())
        private set
    var page by mutableStateOf(initialPage)
    val drafts = mutableStateMapOf<String, String>()
    val chatAttachments = mutableStateMapOf<String, List<TaskAttachment>>()
    val floatingChat = FloatingChatState()
    val chatSelection = ChatContextSelection()
    val taskEdits = mutableStateMapOf<String, TaskEditDraft>()
    val taskReviewDrafts = mutableStateMapOf<String, TaskReviewDraft>()
    val documentDrafts = mutableStateMapOf<String, DocumentDraft>()
    val libraryWorkspace = LibraryWorkspaceState()
    val documentGraph = DocumentGraphPageState()
    val pipelineGraphs = PipelineGraphState()
    val pipelineProgress = PipelineProgressState()
    val workActivity = WorkActivityState()
    val automationToasts = AutomationToastState(scope)
    val versionControlPage = VersionControlPageState()
    val memoryPage = ReferencePageState()
    val skillsPage = ReferencePageState()
    val automationsPage = AutomationsPageState()
    val mcpPage = McpPageState()
    val projectSettingsPage = ProjectSettingsPageState()
    val teamSettingsPage = TeamSettingsPageState(scope) {
        if (Section.from(page) == Section.TEAMS) refresh()
    }
    var pageRefresh by mutableLongStateOf(0L)
        private set

    fun hasUnsavedChanges() = drafts.values.any(String::isNotBlank) || chatAttachments.isNotEmpty() ||
        taskEdits.values.any(TaskEditDraft::dirty) || taskReviewDrafts.values.any { it.attachments.isNotEmpty() } ||
        documentDrafts.values.any(DocumentDraft::dirty) || pipelineGraphs.hasUnsavedChanges() ||
        versionControlPage.hasUnsavedChanges || memoryPage.hasUnsavedChanges || skillsPage.hasUnsavedChanges ||
        automationsPage.hasUnsavedChanges || mcpPage.hasUnsavedChanges || projectSettingsPage.hasUnsavedChanges ||
        teamSettingsPage.hasUnsavedChanges
    var list by mutableStateOf<WorkspaceList?>(null)
        private set
    var detail by mutableStateOf<WorkspaceDetail?>(null)
        private set
    var listError by mutableStateOf<Problem?>(null)
        private set
    var detailError by mutableStateOf<Problem?>(null)
        private set
    var actionError by mutableStateOf<Problem?>(null)
        private set
    var acting by mutableStateOf(false)
        private set
    private var updates: Job? = null
    private var chromePolling: Job? = null
    private var loading: Job? = null
    private var dirty = false

    suspend fun connect() {
        connecting = true
        try {
            bind(studio.open(root))
        } catch (error: Exception) {
            connectionProblem = error.problem("projectUnavailable")
        } finally {
            connecting = false
        }
    }

    fun bind(value: ProjectBinding) {
        if (binding == value) return
        require(value.root == root)
        binding = value
        val client = studio.project(value)
        api = client
        connectionProblem = null
        updates?.cancel()
        refresh(reset = true)
        chromePolling?.cancel()
        chromePolling = scope.launch {
            loadChromeProject(client)
            while (isActive) {
                coroutineScope {
                    launch { chromeCounts = chromeData(client, "/project/task-counts") as? JsonObject }
                    launch { chromeStatus = chromeData(client, "/status") as? JsonObject }
                }
                delay(15_000)
            }
        }
        updates = scope.launch {
            while (isActive) {
                try {
                    studio.events(value).buffer(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST).onEach { event ->
                        workActivity.onEvent(event)
                        pipelineProgress.onEvent(event)
                        automationToasts.onEvent(event)
                        floatingChat.onEvent(event)
                    }.buffer(Channel.CONFLATED).collect { event ->
                        val type = event.type
                        eventsConnected = true
                        if (type.startsWith("plugin.") || type.startsWith("project.")) loadChromeProject(client)
                        val section = Section.from(page)
                        if (type.startsWith("system.") || section.relevant(type)) refresh()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
                eventsConnected = false
                delay(1500)
            }
        }
    }

    fun refreshChrome() {
        val client = api ?: return
        scope.launch { loadChromeProject(client) }
    }

    private suspend fun loadChromeProject(client: ProjectApi) {
        coroutineScope {
            launch { chromeProject = chromeData(client, "/project") as? JsonObject }
            launch { chromePluginPages = (chromeData(client, "/plugins/pages") as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
        }
    }

    private suspend fun chromeData(client: ProjectApi, path: String): JsonElement? = try {
        client.get(path)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    fun navigate(next: Page) {
        if (next == page) return
        chatSelection.clear(page)
        val previous = Section.from(page)
        val destination = Section.from(next)
        if (destination == Section.CHATS && previous != Section.CHATS) floatingChat.returnPage = page
        if (previous == Section.CHATS && destination != Section.CHATS) floatingChat.selection = page.query.chat
        page = next
        if (destination != previous) list = null
        detail = null
        actionError = null
        refresh(reset = true)
    }

    fun refresh(reset: Boolean = false) {
        val client = api ?: return
        if (!Section.from(page).usesWorkspaceList) {
            loading?.cancel()
            pageRefresh++
            return
        }
        if (loading?.isActive == true && !reset) {
            dirty = true
            return
        }
        loading?.cancel()
        loading = scope.launch {
            do {
                dirty = false
                val current = page
                val section = Section.from(current)
                listError = null
                detailError = null
                try {
                    list = client.load(section, current)
                } catch (error: Exception) {
                    listError = error.problem()
                }
                if (section == Section.EXPLORER || section == Section.DOCUMENTS) pageRefresh++
                try {
                    detail = client.detail(section, current, list)
                } catch (error: Exception) {
                    detailError = error.problem()
                }
                if (dirty) delay(300)
            } while (dirty)
        }
    }

    fun action(block: suspend ProjectApi.() -> Unit) {
        val client = api ?: return
        if (acting) return
        acting = true
        actionError = null
        scope.launch {
            try {
                client.block()
                refresh()
                floatingChat.changed()
            } catch (error: Exception) {
                actionError = error.problem()
            } finally {
                acting = false
            }
        }
    }

    fun dispose() = scope.cancel()
}
