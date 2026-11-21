package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.jetbrains.jewel.ui.component.Text

// §10.9: visibility belongs to the launcher, while both presentations share the current draft.
internal class FloatingChatState {
    var visible by mutableStateOf(false)
    var selection by mutableStateOf<String?>(null)
    var starting by mutableStateOf(false)
    var newMemberId by mutableStateOf<String?>(null)
    var returnPage by mutableStateOf<Page?>(null)
    var revision by mutableLongStateOf(0L)
        private set
    private val unread = mutableStateMapOf<String, Unit>()
    private val viewers = mutableMapOf<String, Int>()

    val unreadCount get() = unread.size
    fun isUnread(id: String) = id in unread
    fun forget(id: String) { unread.remove(id) }

    fun watch(id: String) {
        viewers[id] = (viewers[id] ?: 0) + 1
        forget(id)
    }

    fun unwatch(id: String) {
        val count = viewers[id] ?: return
        if (count <= 1) viewers.remove(id) else viewers[id] = count - 1
    }

    fun onEvent(event: StudioEvent) {
        if (Section.CHATS.relevant(event.type) || event.type.startsWith("system.")) changed()
        val message = event.payload ?: return
        val id = message.string("chat_id") ?: return
        when (event.type) {
            "team.chat.deleted" -> forget(id)
            "team.chat.message" -> if (id !in viewers && message.string("role") == "assistant" && message.string("kind") == "text") {
                unread[id] = Unit
            }
        }
    }

    fun changed() { if (visible) revision++ }
}

@Composable
internal fun MarkChatRead(state: FloatingChatState, id: String?) {
    if (id != null && LocalWindowInfo.current.isWindowFocused) DisposableEffect(state, id) {
        state.watch(id)
        onDispose { state.unwatch(id) }
    }
}

internal data class FloatingChatPresentation(
    val list: WorkspaceList?,
    val detail: WorkspaceDetail?,
    val listError: Problem?,
    val detailError: Problem?,
    val close: () -> Unit,
    val expand: () -> Unit,
)

@Composable
internal fun FloatingChatWorkspace(
    tab: ProjectTab,
    size: ChatPanelPreference,
    navigate: (Page) -> Unit,
    reserveLauncherSpace: Boolean,
    content: @Composable () -> Unit,
) {
    val margin = StudioTokens.space3
    // Keep edge-aligned controls and pointer-sensitive charts in a different layout slot
    // from the closed launcher. Only an explicitly opened chat panel overlays the page.
    val launcherStrip = if (reserveLauncherSpace) StudioTokens.chatLauncherSize + margin * 2 else 0.dp
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(bottom = launcherStrip)) { content() }
        FloatingChat(tab, size, navigate, Modifier.padding(margin))
    }
}

@Composable
private fun FloatingChat(tab: ProjectTab, size: ChatPanelPreference, navigate: (Page) -> Unit, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val state = tab.floatingChat
    val launcherFocus = remember { FocusRequester() }
    if (tab.api == null) return
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        val launcherSize = StudioTokens.chatLauncherSize
        val panelOffset = launcherSize + StudioTokens.space3
        val count = state.unreadCount
        val label = when {
            state.visible -> m["floatingChat.close"]
            count > 0 -> m["floatingChat.launcherUnread"].replace("{count}", count.toString())
            else -> m["chats"]
        }
        fun close() {
            state.visible = false
            launcherFocus.requestFocus()
        }
        Box {
            val toggle: () -> Unit = { if (state.visible) close() else state.visible = true }
            StudioAction(label, if (state.visible) "chevron-down" else "message-circle",
                Modifier.size(launcherSize).focusRequester(launcherFocus).studioShadow(CircleShape, colors.shadowLg).semantics {
                    if (state.visible) collapse { close(); true }
                    else expand { state.visible = true; true }
                },
                enabled = true, primary = true, outlined = false, iconOnly = true, danger = false, busy = false, onClick = toggle,
                shape = CircleShape, iconSize = StudioTokens.iconSize + StudioTokens.space1, tint = colors.accent)
            if (!state.visible && count > 0) {
                Text(if (count > 99) "99+" else count.toString(), Modifier.align(Alignment.TopEnd)
                    .offset(StudioTokens.spaceHair, -StudioTokens.spaceHair).background(colors.danger, CircleShape)
                    .padding(horizontal = StudioTokens.space1).clearAndSetSemantics {},
                    color = StudioTokens.ensureContrast(colors.onAccent, colors.danger, StudioTokens.textContrast),
                    fontSize = StudioTokens.textSmall, maxLines = 1)
            }
        }
        if (state.visible) {
            val available = ChatPanelSize(floor(maxWidth.value).toInt(), floor((maxHeight - panelOffset).value).toInt())
            if (available.width > 0 && available.height > 0) {
                FloatingChatPanel(tab, size, available, navigate, ::close, Modifier.align(Alignment.BottomEnd).padding(bottom = panelOffset))
            }
        }
    }
}

@Composable
private fun FloatingChatPanel(
    tab: ProjectTab, size: ChatPanelPreference, available: ChatPanelSize, navigate: (Page) -> Unit,
    close: () -> Unit, modifier: Modifier,
) {
    val colors = localColors.current
    val m = localMessages.current
    val state = tab.floatingChat
    val api = tab.api ?: return
    var list by remember(api) { mutableStateOf<WorkspaceList?>(null) }
    var detail by remember(api) { mutableStateOf<WorkspaceDetail?>(null) }
    var listError by remember(api) { mutableStateOf<Problem?>(null) }
    var detailFailure by remember(api) { mutableStateOf<Pair<String?, Problem>?>(null) }
    LaunchedEffect(api) {
        snapshotFlow { state.selection to state.revision }.collect { (id, _) ->
            val page = Page(Section.CHATS.route, Selectors(chat = id))
            try {
                list = api.load(Section.CHATS, page)
                listError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                listError = error.problem()
            }
            if (id == state.selection) try {
                detail = api.detail(Section.CHATS, page, list)
                detailFailure = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (id == state.selection) {
                    if (error is ApiFailure && error.status == 404) detail = null
                    detailFailure = id to error.problem()
                }
            }
            delay(150)
        }
    }
    val detailError = detailFailure?.takeIf { it.first == state.selection }?.second
    val presentation = FloatingChatPresentation(list, detail, listError, detailError, close) {
        navigate(Page(Section.CHATS.route, Selectors(chat = state.selection)))
    }
    var preview by remember { mutableStateOf<ChatPanelSize?>(null) }
    val shown = (preview ?: size.chosen).fit(available)
    val shape = RoundedCornerShape(StudioTokens.radiusXl)
    val focus = remember { FocusRequester() }
    Box(modifier.size(shown.width.dp, shown.height.dp).studioArrival(distance = StudioTokens.space2, duration = StudioMotion.fast)
        .onPreviewKeyEvent { event ->
            if (event.key == Key.Escape) {
                if (event.type == KeyEventType.KeyUp) close()
                true
            } else false
        }.focusRequester(focus).focusable().semantics { dialog(); contentDescription = m["chats"] }) {
        Box(Modifier.matchParentSize().studioShadow(shape, colors.shadowLg)
            .clip(shape).background(colors.cardFill).border(StudioTokens.borderWidth, colors.cardBorder, shape)) {
            ChatsPage(tab, { state.selection = it.query.chat }, Modifier.fillMaxSize(), presentation)
        }
        // Keep the edge hit bands independent from visual spacing and border width.
        ChatPanelResizeHandle(ChatPanelEdge.LEFT, size.chosen, available, Modifier.align(Alignment.CenterStart)
            .padding(top = StudioTokens.space3, bottom = StudioTokens.space2).fillMaxHeight().width(6.dp), { preview = it }, size::choose)
        ChatPanelResizeHandle(ChatPanelEdge.TOP, size.chosen, available, Modifier.align(Alignment.TopCenter)
            .padding(start = StudioTokens.space3, end = StudioTokens.space2).fillMaxWidth().height(6.dp), { preview = it }, size::choose)
        ChatPanelResizeHandle(ChatPanelEdge.CORNER, size.chosen, available, Modifier.align(Alignment.TopStart).size(StudioTokens.space3),
            { preview = it }, size::choose)
    }
    LaunchedEffect(focus) { focus.requestFocus() }
}
