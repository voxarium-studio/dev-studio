package studio.voxarium.desktop

import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

@Composable
internal fun ChatsPage(tab: ProjectTab, navigate: (Page) -> Unit, modifier: Modifier, popup: FloatingChatPresentation? = null) {
    val m = localMessages.current
    val colors = localColors.current
    val state = tab.floatingChat
    val list = if (popup == null) tab.list else popup.list
    val chats = list?.items.orEmpty()
    val agents = list?.people.orEmpty().filter { it.string("kind") == "agent" }
    val members = list?.people.orEmpty()
    val selection = if (popup == null) tab.page.selection(Section.CHATS) else state.selection
    val active = (if (popup == null) tab.detail else popup.detail)?.takeIf { it.item.id == selection }
    val member = members.find { it.string("id") == active?.data?.string("member_id") }
    var showContext by remember(selection) { mutableStateOf(false) }
    val location = tab.chatLocation()
    MarkChatRead(state, active?.item?.id?.takeUnless { state.starting })

    fun remove(id: String) = tab.deleteConversation(id) {
        val selected = if (popup == null) tab.page.takeIf { it.path == Section.CHATS.route }?.query?.chat else state.selection
        if (selected == id) navigate(Page(Section.CHATS.route))
    }
    val presentationActions: @Composable () -> Unit = {
        if (popup != null) {
            WorkAction(m["floatingChat.expand"], "maximize", outlined = false, iconOnly = true, onClick = popup.expand)
            WorkAction(m["floatingChat.close"], "chevron-down", outlined = false, iconOnly = true, onClick = popup.close)
        } else state.returnPage?.let { returnPage ->
            WorkAction(m["floatingChat.collapse"], "panel-bottom-close", outlined = false, iconOnly = true) {
                state.visible = true
                navigate(returnPage)
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val mobile = popup != null || maxWidth < 640.dp
        val showStatus = popup == null && maxWidth >= 1024.dp
        val railWidth = (maxWidth * .30f).coerceIn(192.dp, 320.dp)
        val listFill = if (popup == null) colors.canvas else colors.cardFill
        val compactHeader = maxWidth < 360.dp
        Row(Modifier.fillMaxSize()) {
            if (!mobile || selection == null && !state.starting) {
                Column((if (mobile) Modifier.fillMaxWidth() else Modifier.width(railWidth)).fillMaxHeight().background(listFill)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = StudioTokens.titlebarHeight).padding(horizontal = StudioTokens.space3),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                        StudioIcon("message-circle", Modifier.size(StudioTokens.iconSize), colors.faint)
                        Text(m["chats"], Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (agents.isNotEmpty()) WorkAction(m["newChat"], "plus", outlined = false, iconOnly = compactHeader) {
                            state.newMemberId = null
                            state.starting = true
                            navigate(Page("/chats"))
                        }
                        if (mobile) presentationActions()
                    }
                    Rule()
                    (if (popup == null) tab.listError else popup.listError)?.let { Box(Modifier.padding(StudioTokens.space3)) { ProblemText(it) } }
                    if (active == null && !state.starting) tab.actionError?.let { Box(Modifier.padding(StudioTokens.space3)) { ProblemText(it) } }
                    when {
                        list == null -> Muted(m["loading"], modifier = Modifier.padding(StudioTokens.space4))
                        chats.isEmpty() -> WorkEmpty(m["work.chatEmpty"], modifier = Modifier.weight(1f))
                        else -> ScrollList(Modifier.weight(1f).fillMaxWidth().padding(vertical = StudioTokens.space1)) {
                            items(chats, key = { it.id }) { chat ->
                                val owner = members.find { it.string("id") == chat.payload.string("member_id") }
                                ChatListRow(chat, owner, chat.id == selection, state.isUnread(chat.id), listFill,
                                    if (tab.acting) null else ({ remove(chat.id) })) {
                                    state.starting = false
                                    navigate(Page("/chats", Selectors(chat = chat.id)))
                                }
                            }
                        }
                    }
                }
                if (!mobile) Rule(vertical = true)
            }

            if (!mobile || selection != null || state.starting) {
                Column(Modifier.weight(1f).fillMaxHeight().background(if (popup == null) colors.surface else colors.cardFill)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = StudioTokens.titlebarHeight).padding(horizontal = StudioTokens.space2),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                        if (mobile && (selection != null || state.starting)) {
                            WorkAction(m["work.chatAll"], "arrow-left", outlined = false, iconOnly = true) {
                                state.starting = false
                                navigate(Page("/chats"))
                            }
                        }
                        if (member != null) {
                            MemberAvatar(member, badge = true)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
                                Text(member.string("name").orEmpty(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val subtitle = if (active?.data?.flag("is_running") == true) m["work.chatAnswering"] else member.string("role_title")
                                subtitle?.takeIf(String::isNotBlank)?.let {
                                    Text(it, color = colors.muted, fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else {
                            if (!state.starting && selection == null) StudioIcon("message-circle", Modifier.size(StudioTokens.iconSize), colors.faint)
                            Text(m[if (state.starting) "newChat" else "chats"], Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        }
                        if (active != null) {
                            if (member?.string("kind") != "agent") {
                                WorkAction(m["contextViewer.title"], "file-search", outlined = false, iconOnly = true) { showContext = true }
                            }
                            WorkAction(m["work.chatDelete"], "trash-2", enabled = !tab.acting,
                                outlined = false, iconOnly = true) { remove(active.item.id) }
                        }
                        presentationActions()
                    }
                    if (active != null && member?.string("kind") == "agent") {
                        ChatModelChips(tab, active.data, member) { showContext = true }
                    }
                    Rule()
                    when {
                        state.starting -> {
                            val target = agents.find { it.string("id") == state.newMemberId }
                            Row(Modifier.fillMaxWidth().padding(StudioTokens.space3), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                                Muted(m["work.chatTalkTo"])
                                val options = agents.mapNotNull { agent -> agent.string("id")?.let { it to agent.string("name").orEmpty() } }.toMap()
                                StudioChoice(m["work.chatTalkTo"], state.newMemberId.orEmpty(), options, m["work.chatPickMember"],
                                    Modifier.weight(1f), enabled = !tab.acting) { state.newMemberId = it }
                            }
                            Rule()
                            WorkEmpty(m["work.chatNewHint"], modifier = Modifier.weight(1f))
                            ChatComposer(tab, "new-chat", location, target != null, null) { sent ->
                                val id = state.newMemberId ?: return@ChatComposer
                                tab.action {
                                    val created = createConversation(id, producer = false, language = m.language)
                                    tab.moveChatDraft("new-chat", created)
                                    if (state.starting) {
                                        state.starting = false
                                        state.selection = created
                                        if (popup != null && tab.page.path == Section.CHATS.route) popup.expand()
                                        if (tab.page.path == Section.CHATS.route && popup == null) navigate(Page("/chats", Selectors(chat = created)))
                                    }
                                    postMessage(created, location, sent)
                                    tab.clearSentChatDraft(created, sent, location)
                                }
                            }
                        }
                        active != null -> {
                            if (member?.string("builtin") == "producer") ProducerControls(tab, active.data)
                            ChatBody(tab, active, member, location, Modifier.weight(1f))
                        }
                        selection != null -> Centered {
                            val error = if (popup == null) tab.detailError else popup.detailError
                            if (error != null) ProblemText(error) else Muted(m["loading"])
                        }
                        else -> WorkEmpty(m["work.chatChoose"])
                    }
                }
            }
            if (active != null && showStatus) {
                Rule(vertical = true)
                WorkSessionStatus(active.data.objects("messages"), Modifier.width(298.dp).fillMaxHeight())
            }
        }
        if (showContext && active != null) {
            NativeContextViewer(tab, NativeContextTarget.Chat(active.item.id), close = { showContext = false },
                revision = active.data.string("updated_at"))
        }
    }
}

@Composable
private fun ChatListRow(
    item: WorkspaceItem, member: JsonObject?, selected: Boolean, unread: Boolean, background: Color,
    remove: (() -> Unit)?, open: () -> Unit,
) {
    val colors = localColors.current
    val m = localMessages.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = studioColor(when {
        selected -> colors.selected
        hovered -> colors.hover
        else -> background
    })
    val ink = if (selected) colors.onAccentContainer else colors.text
    val captionInk = if (selected) colors.onAccentContainer else colors.muted
    val snippet = item.payload.string("title")?.takeIf(String::isNotBlank) ?: m["newChat"]
    StudioContextTarget(snippet, listOfNotNull(remove?.let {
        StudioMenuItem(m["work.delete"], icon = "trash-2", danger = true, onClick = it)
    }), Modifier.fillMaxWidth()) { target ->
        Row(Modifier.fillMaxWidth().then(target)
            .studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), background = fill)
            .background(fill).hoverable(interaction).clickable(role = Role.Button, onClick = open)
            .semantics {
                this.selected = selected
                if (unread) stateDescription = m["floatingChat.unread"]
            }
            .padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space2),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(member, badge = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(member?.string("name") ?: item.caption ?: item.payload.string("member_id") ?: m["chats"],
                        Modifier.weight(1f), color = ink, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val time = conversationTime(item.payload, "last_message_at").ifEmpty { conversationTime(item.payload, "created_at") }
                    Text(time.ifEmpty { "—" }, color = captionInk, fontSize = StudioTokens.textSmall, maxLines = 1, softWrap = false)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
                    if (item.payload.flag("is_running")) WorkDot(colors.accent, live = true)
                    else if (unread) WorkDot(colors.accent)
                    Text(snippet, Modifier.weight(1f, fill = false), color = captionInk, fontSize = StudioTokens.textSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.payload.flag("producer_mode")) {
                        Text(m["producer"], Modifier.background(colors.selected, CircleShape).padding(horizontal = StudioTokens.space1),
                            fontSize = StudioTokens.textSmall, color = colors.onAccentContainer, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun conversationTime(message: JsonObject, field: String): String {
    val language = localMessages.current.language
    val format = remember(language) {
        DateTimeFormatter.ofPattern(if (language == "ru") "d MMM, HH:mm" else "MMM d, h:mm a", Locale.forLanguageTag(language))
    }
    return message.conversationTimestamp(field)?.atZone(ZoneId.systemDefault())?.format(format).orEmpty()
}

// The API emits UTC while retained/demo records may still use SQLite's space separator.
internal fun JsonObject.conversationTimestamp(field: String): Instant? {
    val raw = string(field)?.trim()?.replaceFirst(' ', 'T') ?: return null
    return runCatching { Instant.parse(raw) }
        .recoverCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
}

@Composable
internal fun ProducerPage(tab: ProjectTab, navigate: (Page) -> Unit, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val list = tab.list
    val chats = list?.items.orEmpty()
    val selection = tab.page.query.chat
    val active = tab.detail?.takeIf { it.item.id == selection }
    val members = list?.people.orEmpty()
    val owner = members.find { it.string("id") == active?.data?.string("member_id") }
    val producer = owner ?: members.firstOrNull { it.string("builtin") == "producer" }
    val producerId = producer?.string("id")
    val location = tab.chatLocation()
    MarkChatRead(tab.floatingChat, active?.item?.id)
    var showContext by remember(selection) { mutableStateOf(false) }
    var openingBlank by remember { mutableStateOf(false) }

    LaunchedEffect(list, selection) {
        if (selection == null && !openingBlank) chats.firstOrNull()?.let { navigate(Page("/producer", Selectors(chat = it.id))) }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val pinnedWidth = if (maxWidth < 900.dp) (maxWidth / 2).coerceAtLeast(280.dp)
            else (maxWidth * .26f + 200.dp).coerceIn(480.dp, 580.dp)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(pinnedWidth).fillMaxHeight().clip(RoundedCornerShape(StudioTokens.radiusLg)).background(colors.surface)) {
                active?.let { ProducerControls(tab, it.data, toolbar = true) }
                Row(Modifier.fillMaxWidth().heightIn(min = StudioTokens.titlebarHeight).padding(horizontal = StudioTokens.space3),
                    horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                    val options = chats.associate { chat ->
                        chat.id to (chat.payload.string("title")?.takeIf(String::isNotBlank) ?: m["work.producerChat"])
                    }
                    StudioChoice(m["work.producerChat"], selection.orEmpty(), options, m["newChat"],
                        Modifier.weight(1f), enabled = !tab.acting) { id ->
                        openingBlank = false
                        navigate(Page("/producer", Selectors(chat = id)))
                    }
                    WorkAction(m["newChat"], "plus", enabled = producerId != null && !tab.acting, iconOnly = true) {
                        if (producerId != null) tab.action {
                            val id = createConversation(producerId, producer = true, language = m.language)
                            if (tab.page.path == "/producer") navigate(Page("/producer", Selectors(chat = id)))
                        }
                    }
                    WorkAction(m["work.producerDelete"], "trash-2", enabled = active != null && !tab.acting,
                        iconOnly = true, danger = true) {
                        val id = active?.item?.id ?: return@WorkAction
                        tab.deleteConversation(id) {
                            if (tab.page.query.chat == id) {
                                val next = chats.firstOrNull { it.id != id }?.id
                                openingBlank = next == null
                                navigate(Page("/producer", Selectors(chat = next)))
                            }
                        }
                    }
                }
                if (producer != null && active != null) ChatModelChips(tab, active.data, producer) { showContext = true }
                Rule()
                WorkErrors(tab, includeDetail = true)
                when {
                    list == null -> Muted(m["work.producerLoading"], modifier = Modifier.padding(StudioTokens.space4))
                    producer == null -> Muted(m["work.producerMissing"], modifier = Modifier.padding(StudioTokens.space4))
                    else -> {
                        ProducerChecklist(active?.data, navigate, Modifier.weight(1f).fillMaxWidth())
                        Rule()
                        if (active != null) ChatBody(tab, active, producer, location, Modifier.weight(2f))
                        else Column(Modifier.weight(2f).fillMaxWidth()) {
                            WorkEmpty(m["work.chatNewHint"], modifier = Modifier.weight(1f))
                            ChatComposer(tab, "new-producer", location, producerId != null, null) { sent ->
                                if (producerId == null) return@ChatComposer
                                val source = tab.page
                                tab.action {
                                    val id = createConversation(producerId, producer = true, language = m.language)
                                    tab.moveChatDraft("new-producer", id)
                                    if (tab.page == source) navigate(Page("/producer", Selectors(chat = id)))
                                    postMessage(id, location, sent)
                                    tab.clearSentChatDraft(id, sent, location)
                                }
                            }
                        }
                    }
                }
            }
            ProducerTaskStrip(tab, list?.activeTasks.orEmpty(), list?.people.orEmpty(), navigate, Modifier.weight(1f).fillMaxHeight())
        }
        if (showContext && active != null) {
            NativeContextViewer(tab, NativeContextTarget.Chat(active.item.id), close = { showContext = false },
                revision = active.data.string("updated_at"))
        }
    }
}

private suspend fun ProjectApi.createConversation(member: String, producer: Boolean, language: String): String {
    val result = post("/chats", buildJsonObject {
        put("member_id", member)
        put("producer_mode", producer)
        put("locale", language)
    }).jsonObject
    val id = requireNotNull(result.string("id"))
    if (producer) post("/chats/${segment(id)}/producer/workspace", jsonFields("locale" to language))
    return id
}

internal data class ChatDraft(val text: String, val attachments: List<TaskAttachment>) {
    val hasContent: Boolean get() = text.isNotBlank() || attachments.isNotEmpty()

    // §10.9: uploads belong to the bound project and the API resolves their references.
    fun request(location: ChatLocation) = buildJsonObject {
        put("text", text)
        put("attachments", attachments.attachmentReferences())
        putJsonObject("context") {
            put("page", Messages("en")[location.section.label])
            put("route", location.page.path)
            put("title", location.title)
            put("document", location.document)
            put("file", location.file)
            put("selection", location.selection)
        }
    }
}

internal data class ChatLocation(val page: Page, val title: String = "", val file: String? = null, val selection: String? = null) {
    val section = Section.from(page)
    val document = page.query.path.takeIf { section == Section.DOCUMENTS || section == Section.DOCUMENT_GRAPH }
}

private fun ProjectTab.chatLocation(): ChatLocation {
    val section = Section.from(page)
    val selected = page.selection(section)
    val item = detail?.item?.takeIf { it.id == selected }
    val file = selected.takeIf { section == Section.EXPLORER && item != null && !item.payload.flag("is_dir") }
    return ChatLocation(page, item?.title.orEmpty(), file, chatSelection.text(page))
}

private suspend fun ProjectApi.postMessage(id: String, location: ChatLocation, draft: ChatDraft) {
    post("/chats/${segment(id)}/messages", draft.request(location))
}

private fun ProjectTab.moveChatDraft(source: String, id: String) {
    drafts.remove(source)?.let { drafts[id] = it }
    chatAttachments.remove(source)?.let { chatAttachments[id] = it }
}

private fun ProjectTab.clearSentChatDraft(id: String, sent: ChatDraft, location: ChatLocation) {
    if (drafts[id].orEmpty() == sent.text) drafts.remove(id)
    if (chatAttachments[id].orEmpty() == sent.attachments) chatAttachments.remove(id)
    location.selection?.let { chatSelection.clear(location.page, it) }
}

private fun ProjectTab.deleteConversation(id: String, removed: () -> Unit) {
    action {
        delete("/chats/${segment(id)}")
        floatingChat.forget(id)
        drafts.remove(id)
        chatAttachments.remove(id)
        removed()
    }
}

@Composable
private fun ChatBody(tab: ProjectTab, active: WorkspaceDetail, member: JsonObject?, location: ChatLocation, modifier: Modifier) {
    val m = localMessages.current
    val id = active.item.id
    val data = active.data
    val running = data.flag("is_running")
    Column(modifier.fillMaxWidth().studioArrival(id, distance = StudioTokens.space2)) {
        ConversationTranscript(data.objects("messages"), id, member?.string("name"),
            m["work.chatTranscriptEmpty"].replace("{name}", member?.string("name") ?: m.status("assistant")),
            Modifier.weight(1f).fillMaxWidth(), running = running,
            afterMessage = { message ->
                TaskAttachmentLinks(tab.api, message.obj("data")?.taskAttachments(brief = true).orEmpty())
                ChatSentContext(message)
            })
        if (member == null || member.string("kind") == "human") {
            Rule()
            Muted(m["work.chatRemoved"], modifier = Modifier.padding(StudioTokens.space3))
            tab.actionError?.let { Box(Modifier.padding(StudioTokens.space3)) { ProblemText(it) } }
        } else {
            ChatComposer(tab, id, location, !data.flag("producer_paused"),
                if (running) ({ tab.action { post("/chats/${segment(id)}/cancel") } }) else null,
                backendError = data.string("error")) { sent ->
                tab.action {
                    postMessage(id, location, sent)
                    tab.clearSentChatDraft(id, sent, location)
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    tab: ProjectTab,
    key: String,
    location: ChatLocation,
    enabled: Boolean,
    stop: (() -> Unit)?,
    backendError: String? = null,
    send: (ChatDraft) -> Unit,
) {
    val m = localMessages.current
    val colors = localColors.current
    val draft = tab.drafts[key].orEmpty()
    var text by remember(key) { mutableStateOf(TextFieldValue(draft)) }
    LaunchedEffect(draft) { if (draft != text.text) text = TextFieldValue(draft) }
    val attachments = tab.chatAttachments[key].orEmpty()
    val composed = ChatDraft(text.text, attachments)
    val canSend = composed.hasContent && enabled && !tab.acting
    val submit = { if (canSend) send(composed) }
    Rule()
    Column(Modifier.fillMaxWidth().padding(start = StudioTokens.space3, end = StudioTokens.space3, top = StudioTokens.space2, bottom = StudioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        tab.actionError?.let { ProblemText(it) }
        backendError?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.danger, fontSize = StudioTokens.textSmall) }
        val place = location.document ?: location.file
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) {
                WorkPill(place?.substringAfterLast('/') ?: m[location.section.label], quiet = false)
            }
            location.selection?.let { selected ->
                WorkPill(chatSelectionLabel(selected, m), quiet = false)
                WorkAction(m["floatingChat.clearSelection"], "x", outlined = false, iconOnly = true) {
                    tab.chatSelection.clear(location.page)
                }
            }
        }
        key(key) {
            TaskAttachmentPicker(tab, attachments, onChange = {
                if (it.isEmpty()) tab.chatAttachments.remove(key) else tab.chatAttachments[key] = it
            }) { paste ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.Bottom) {
                    TextArea(value = text, onValueChange = {
                        text = it
                        if (it.text.isEmpty()) tab.drafts.remove(key) else tab.drafts[key] = it.text
                    }, modifier = Modifier.weight(1f).heightIn(min = StudioTokens.iconButtonSize, max = 150.dp).then(paste)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                                submit()
                                true
                            } else false
                        }
                        .semantics { contentDescription = m["message"] }, placeholder = { Muted(m["message"]) })
                    if (stop != null) WorkAction(m["stopReply"], "square", enabled = !tab.acting, iconOnly = true, onClick = stop)
                    WorkAction(m["send"], "send", enabled = canSend, iconOnly = true, primary = true, onClick = submit)
                }
            }
        }
    }
}

private fun chatSelectionLabel(text: String, m: Messages) = m["floatingChat.selection"].replace("{count}", text.length.toString())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatSentContext(message: JsonObject) {
    val context = message.obj("data")?.obj("context") ?: return
    val m = localMessages.current
    val location = context.string("route")?.substringBefore('?')?.let { Section.from(Page(it)) }
    val place = context.string("document") ?: context.string("file")
    val text = context.string("selection")?.takeIf(String::isNotBlank)
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            val label = location?.takeUnless { it == Section.TOOLS }?.let { m[it.label] } ?: context.string("page")
            label?.takeIf(String::isNotBlank)?.let { WorkPill(it) }
            place?.takeIf(String::isNotBlank)?.let { WorkPill(it.substringAfterLast('/')) }
            if (text != null) WorkAction(chatSelectionLabel(text, m), if (expanded) "chevron-up" else "chevron-down", outlined = false) {
                expanded = !expanded
            }
        }
        if (text != null) StudioDisclosure(expanded) {
            SelectionContainer { Text(text, Modifier.fillMaxWidth().padding(StudioTokens.space2), fontSize = StudioTokens.textSmall) }
        }
    }
}

@Composable
private fun ProducerControls(tab: ProjectTab, chat: JsonObject, toolbar: Boolean = false) {
    val m = localMessages.current
    val colors = localColors.current
    val id = chat.string("id") ?: return
    val endpoint = "/chats/${segment(id)}/producer"
    val state = chat.string("producer_state") ?: "off"
    val running = chat.flag("is_running")
    val paused = chat.flag("producer_paused")
    val started = chat.flag("producer_started")
    val enabled = !tab.acting && !running && !paused
    val savedEstimate = chat.string("estimate_task_cost_usd").orEmpty().removeSuffix(".0")
    var estimate by remember(id, savedEstimate) { mutableStateOf(TextFieldValue(savedEstimate)) }
    val amount = estimate.text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    Column(Modifier.fillMaxWidth().background(colors.inset).padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space2),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            StudioIcon("clapperboard", Modifier.size(StudioTokens.iconSize), colors.accent)
            Text(m["producer"], fontWeight = FontWeight.Medium)
            if (toolbar && !started) WorkPill(m["work.producerNotStarted"])
            else WorkStatus(if (paused) "paused" else state, quiet = true)
            if (started) {
                WorkAction(m[if (paused) "resume" else "pause"], if (paused) "play" else "pause", enabled = !tab.acting) {
                    tab.action { post("$endpoint/${if (paused) "resume" else "pause"}") }
                }
            } else {
                WorkAction(m["startProducer"], "play", enabled = !tab.acting && !running, primary = !toolbar) {
                    tab.action { post("$endpoint/start", jsonFields("locale" to m.language)) }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            val policy = chat.string("producer_policy") ?: "careful"
            for (choice in listOf("careful", "independent")) {
                WorkAction(m["work.producerPolicy.$choice"], null, enabled = enabled, primary = policy == choice) {
                    tab.action { patch("$endpoint/policy", jsonFields("policy" to choice)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                Muted(m["work.producerTaskCost"])
                StudioField(m["work.producerTaskCost"], estimate, { estimate = it }, Modifier.width(86.dp),
                    enabled = !tab.acting && !running)
                if (estimate.text != savedEstimate) WorkAction(m["work.save"], null, enabled = amount != null && !tab.acting && !running) {
                    if (amount != null) tab.action {
                        patch("$endpoint/settings", buildJsonObject { put("estimate_task_cost_usd", amount) })
                    }
                }
            }
            val effort = chat.string("producer_effort_mode") ?: "agent"
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                Muted(m["work.producerEffort"])
                StudioSelect(m["work.producerEffort"], if (effort == "auto") effort else "agent",
                    listOf("agent", "auto").associateWith { m["work.producerEffort.$it"] },
                    Modifier.width(104.dp), enabled = enabled) { choice ->
                    tab.action { patch("$endpoint/effort", jsonFields("mode" to choice)) }
                }
            }
        }
        val version = chat.string("producer_plan_version")?.toIntOrNull()
        if (version != null && state == "awaiting_approval") {
            chat.string("producer_plan_summary")?.takeIf(String::isNotBlank)?.let { Muted(it) }
            WorkAction(m["approvePlan"], "play", primary = true, enabled = enabled) {
                tab.action { post("$endpoint/approve", buildJsonObject { put("version", version) }) }
            }
        }
    }
    Rule()
}

@Composable
private fun ProducerChecklist(chat: JsonObject?, navigate: (Page) -> Unit, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    Column(modifier) {
        Text(m["work.producerPlan"], Modifier.padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space3), fontWeight = FontWeight.Medium)
        val checklist = chat?.objects("producer_checklist").orEmpty()
        ScrollList(Modifier.weight(1f).fillMaxWidth().padding(horizontal = StudioTokens.space4)) {
            items(checklist) { item ->
                Column(Modifier.padding(bottom = StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    WorkCheckbox(item.string("text").orEmpty(), item.flag("done"))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        for (task in item.objects("tasks")) {
                            val id = task.string("id") ?: continue
                            val label = task.string("display_id") ?: continue
                            val status = task.string("status").orEmpty()
                            val shape = RoundedCornerShape(StudioTokens.radiusSm)
                            Row(Modifier.studioFocusRing(shape, background = colors.surface).clip(shape)
                                .semantics { stateDescription = m.status(status) }
                                .clickable(role = Role.Button) { navigate(Page("/tasks", Selectors(task = id))) }
                                .heightIn(min = StudioTokens.controlMinHeight)
                                .padding(horizontal = StudioTokens.space1),
                                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
                                verticalAlignment = Alignment.CenterVertically) {
                                WorkDot(colors.statusColor(status, colors.surface))
                                Text(label, color = colors.faint, fontSize = StudioTokens.textSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProducerTaskStrip(
    tab: ProjectTab,
    tasks: List<JsonObject>,
    people: List<JsonObject>,
    navigate: (Page) -> Unit,
    modifier: Modifier,
) {
    val m = localMessages.current
    val colors = localColors.current
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val reduced = LocalStudioReducedMotion.current
    fun moveBy(delta: Int) {
        if (tasks.isEmpty()) return
        val next = (scroll.firstVisibleItemIndex + delta).coerceIn(0, tasks.lastIndex)
        scope.launch { if (reduced) scroll.scrollToItem(next) else scroll.animateScrollToItem(next) }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = StudioTokens.titlebarHeight).padding(horizontal = StudioTokens.space4),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            StudioIcon("messages-square", Modifier.size(StudioTokens.iconSize), colors.faint)
            Text(m["work.producerTasks"], fontWeight = FontWeight.Medium)
            WorkPill(tasks.size.toString())
            if (tasks.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                WorkAction(m["work.previous"], "chevron-left", enabled = scroll.canScrollBackward, iconOnly = true) {
                    moveBy(-1)
                }
                WorkAction(m["work.next"], "chevron-right", enabled = scroll.canScrollForward, iconOnly = true) {
                    moveBy(1)
                }
            }
        }
        if (tasks.isEmpty()) {
            WorkEmpty(m["work.producerEmpty"], m["work.producerEmptyHint"], "messages-square", Modifier.weight(1f))
        } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val card = ((maxWidth + 176.dp) / 2.5f - 100.dp).coerceAtLeast(360.dp)
            LazyRow(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(start = StudioTokens.space3, bottom = StudioTokens.space2),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                items(tasks, key = { it.string("id").orEmpty() }) { task ->
                    val id = task.string("id").orEmpty()
                    val member = people.find { it.string("id") == task.string("assignee_id") }
                    Box(Modifier.animateItem(
                        fadeInSpec = tween(if (reduced) StudioMotion.quick else StudioMotion.normal, easing = StudioMotion.standard),
                        placementSpec = if (reduced) null else tween(StudioMotion.normal, easing = StudioMotion.inOut),
                        fadeOutSpec = tween(StudioMotion.quick),
                    ).width(card).fillMaxHeight().clip(RoundedCornerShape(StudioTokens.radiusLg)).background(colors.surface)) {
                        ProducerTaskCard(tab, task, member) { navigate(Page("/tasks", Selectors(task = id))) }
                    }
                }
            }
            HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

@Composable
private fun ProducerTaskCard(tab: ProjectTab, task: JsonObject, member: JsonObject?, open: () -> Unit) {
    val endpoint = taskEndpoint(task)
    val client = tab.api
    var detail by remember(endpoint) { mutableStateOf<JsonObject?>(null) }
    var error by remember(endpoint) { mutableStateOf<Problem?>(null) }
    LaunchedEffect(client, task) {
        if (client == null || endpoint == null) return@LaunchedEffect
        try {
            detail = client.get(endpoint).jsonObject
            error = null
        } catch (failure: Exception) {
            error = failure.problem()
        }
    }
    val problem = error
    if (problem != null) Box(Modifier.padding(StudioTokens.space3)) { ProblemText(problem) }
    else TaskConversationView(tab, detail ?: task, close = open, inline = true, memberFallback = member)
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun ConversationMarkdown(text: String, selectable: Boolean = true) {
    ProvideMarkdownStyling {
        Markdown(markdown = text, selectable = selectable, onUrlClick = { link ->
            val uri = runCatching { URI(link) }.getOrNull()
            if (uri?.host != null && uri.userInfo == null && uri.scheme.lowercase() in setOf("https", "http") &&
                java.awt.Desktop.isDesktopSupported()) {
                runCatching { java.awt.Desktop.getDesktop().browse(uri) }
            }
        })
    }
}

@Composable
internal fun ConversationTranscript(
    messages: List<JsonObject>,
    id: String,
    name: String?,
    empty: String,
    modifier: Modifier,
    running: Boolean = false,
    chatSelectionTab: ProjectTab? = null,
    scrollState: LazyListState? = null,
    before: LazyListScope.() -> Unit = {},
    afterMessage: @Composable (JsonObject) -> Unit = {},
) {
    val m = localMessages.current
    val colors = localColors.current
    val visible = messages.filter {
        it.string("kind") !in setOf("file_observation_status", "skills", "mcp", "usage_update") &&
            !(it.string("kind") == "system" && it.string("text") == "usage_update") &&
            (!it.string("text").isNullOrBlank() || it.obj("data")?.objects("attachments").orEmpty().isNotEmpty())
    }
    var follow by remember(id) { mutableStateOf(true) }
    var verbosity by remember(id) { mutableStateOf("default") }
    val verbosityChoices = listOf("default", "verbose").associateWith { m["work.verbosity.$it"] }
    val measure = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current
    val widest = verbosityChoices.values.maxOf { measure.measure(it, textStyle, maxLines = 1).size.width }
    // Keep the complete localized choice visible in the closed select, including its chevron and insets.
    val selectWidth = with(LocalDensity.current) { widest.toDp() } + StudioTokens.iconSize +
        StudioTokens.space2 * 3 + StudioTokens.borderWidth * 2
    val latest = remember(messages) {
        messages.asReversed().firstNotNullOfOrNull { message ->
            workActivityText(message)?.let { message to it }
        }
    }
    val scroll = scrollState ?: key(id) { rememberLazyListState() }
    val count by remember(scroll) { derivedStateOf { scroll.layoutInfo.totalItemsCount } }
    LaunchedEffect(count, visible.lastOrNull()?.string("id"), running, follow) {
        if (follow && count > 0) scroll.requestScrollToItem(count - 1)
    }
    BoxWithConstraints(modifier) {
      val compact = maxWidth < 440.dp
      val inset = if (compact) StudioTokens.space3 else StudioTokens.panePad
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty() && !running && empty.isNotBlank()) {
                Text(empty, modifier = Modifier.padding(inset), color = colors.faint)
            }
            val timeline: @Composable () -> Unit = {
                LazyColumn(Modifier.fillMaxSize(), state = scroll,
                    contentPadding = PaddingValues(horizontal = inset, vertical = StudioTokens.space2),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    before()
                    itemsIndexed(visible, key = { index, message -> message.string("id") ?: index }) { _, message ->
                        TranscriptEntry(message, name, verbose = verbosity == "verbose", selectable = chatSelectionTab == null)
                        afterMessage(message)
                    }
                }
            }
            if (chatSelectionTab == null) timeline()
            else ChatReadableSelection(chatSelectionTab, chatSelectionTab.page, Modifier.matchParentSize(), content = timeline)
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        Rule()
        Row(Modifier.fillMaxWidth().heightIn(min = StudioTokens.rowHeight).padding(horizontal = inset),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            if (running) WorkTyping(colors.accent, m["work.chatAnswering"])
            if (verbosity != "verbose" && latest != null) {
                val (message, line) = latest
                key(id) {
                    WorkActivityLine(line, message.string("id") ?: message, Modifier.weight(1f))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (!compact) Muted(m["work.verbosity"])
            StudioSelect(m["work.verbosity"], verbosity, verbosityChoices,
                Modifier.width(maxOf(selectWidth, if (colors.textSize >= 16f) 184.dp else 110.dp))) { verbosity = it }
            WorkCheckbox(m["work.follow"], follow) { follow = it }
        }
      }
    }
}

@Composable
internal fun WorkSessionStatus(
    messages: List<JsonObject>,
    modifier: Modifier,
    task: JsonObject? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val m = localMessages.current
    val colors = localColors.current
    val skillRecord = messages.lastOrNull { it.string("kind") == "skills" }?.obj("data")
    val skillNames = skillRecord?.stringList("skills").orEmpty() + skillRecord?.stringList("missing").orEmpty()
    val configuredServers = messages.lastOrNull { it.string("kind") == "mcp" }?.obj("data")?.stringList("servers").orEmpty()
    val servers = configuredServers + messages.mapNotNull { message ->
        if (message.string("kind") != "tool_use") null
        else message.obj("data")?.string("name")?.takeIf { it.startsWith("mcp__") }?.removePrefix("mcp__")?.substringBefore("__")
    }
    val delegated = remember(messages, task) { delegatedWork(messages, task?.objects("subagents").orEmpty()) }
    val running = delegated.count { it.running }
    Column(modifier.background(colors.canvas)) {
        Text(m["work.sessionStatus"].uppercase(Locale.forLanguageTag(m.language)),
            Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space3),
            color = colors.muted, fontSize = StudioTokens.textSmall, fontWeight = FontWeight.SemiBold)
        Rule()
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
          Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space1),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            SessionFold(m["work.sessionDelegated"], "bot", delegated.size, empty = m["work.sessionNoDelegated"], running = running) {
                delegated.forEach { entry ->
                    Row(Modifier.padding(start = StudioTokens.space4), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (entry.running) WorkDot(colors.warning, live = true)
                        Muted(entry.description, modifier = Modifier.weight(1f))
                    }
                }
            }
            SessionFold(m["skills"], "graduation-cap", skillNames.distinct().size) {
                if (skillNames.isEmpty()) Muted(m["work.sessionNoSkills"], modifier = Modifier.padding(start = StudioTokens.space4))
                skillNames.distinct().forEach { skill ->
                    Muted(skillRecord?.obj("labels")?.string(skill) ?: skill, modifier = Modifier.padding(start = StudioTokens.space4))
                }
            }
            SessionFold(m["mcp"], "plug", servers.distinct().size) {
                if (servers.isEmpty()) Muted(m["work.sessionNoMcp"], modifier = Modifier.padding(start = StudioTokens.space4))
                servers.distinct().forEach { Muted(it, modifier = Modifier.padding(start = StudioTokens.space4)) }
            }
            if (task != null) {
                val paths = task.stringList("write_paths")
                SessionFold(m["work.sessionWritePaths"], "file-pen", paths.size) {
                    if (paths.isEmpty()) Muted(m["work.sessionNoWritePaths"], modifier = Modifier.padding(start = StudioTokens.space4))
                    paths.forEach { Muted(it, modifier = Modifier.padding(start = StudioTokens.space4)) }
                }
            }
            content()
          }
          if (scroll.maxValue > 0) VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionFold(
    label: String,
    icon: String,
    count: Int,
    empty: String? = null,
    running: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = localColors.current
    val m = localMessages.current
    var expanded by remember { mutableStateOf(false) }
    val canExpand = count > 0 || empty == null
    val shape = RoundedCornerShape(StudioTokens.radiusSm)
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Row(Modifier.fillMaxWidth().studioFocusRing(shape, canExpand, colors.canvas).clip(shape)
            .then(if (canExpand) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier)
            .heightIn(min = StudioTokens.controlMinHeight).padding(vertical = StudioTokens.space1),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
            StudioIcon(icon, Modifier.size(StudioTokens.compactIconSize), if (icon == "graduation-cap") colors.pink else colors.faint)
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Text(label, color = colors.muted, fontWeight = FontWeight.Medium,
                    fontSize = StudioTokens.textSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (count == 0 && empty != null) empty else count.toString(),
                    color = colors.faint, fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (running > 0) {
                    WorkPill("${m.status("running")} · $running", listOf(colors.statusColor("running", colors.inset)),
                        quiet = false, live = true)
                }
            }
            if (canExpand) {
                WorkFoldChevron(expanded, "chevron-down", 180f)
            }
        }
        StudioDisclosure(expanded && canExpand) {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1), content = content)
        }
    }
}

@Composable
private fun TranscriptEntry(message: JsonObject, name: String?, verbose: Boolean, selectable: Boolean) {
    val m = localMessages.current
    val colors = localColors.current
    val role = message.string("role")
    val kind = message.string("kind").orEmpty()
    val text = message.string("text").orEmpty()
    val time = conversationTime(message, "created_at")
    val user = role == "user" || kind in setOf("user", "human", "note", "producer_plan")
    val folded = kind in setOf("thinking", "tool_use", "tool_result")
    val history = kind in setOf("status", "edited", "commit", "continued", "system")
    var expanded by remember(verbose) { mutableStateOf(verbose) }
    when {
        user && kind !in setOf("status", "edited", "commit", "continued") -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.fillMaxWidth(.9f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                if (text.isNotBlank()) Box(Modifier.background(colors.selected, RoundedCornerShape(topStart = StudioTokens.radiusXl, topEnd = StudioTokens.radiusXl, bottomStart = StudioTokens.radiusXl, bottomEnd = StudioTokens.radiusSm))
                    .padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space2)) {
                    if (selectable) SelectionContainer { Text(text, color = colors.onAccentContainer) }
                    else Text(text, color = colors.onAccentContainer)
                }
                if (time.isNotBlank()) Muted(time)
            }
        }
        folded -> Column(Modifier.fillMaxWidth().border(StudioTokens.borderWidth, colors.subtleBorder, RoundedCornerShape(StudioTokens.radiusMd))) {
            Row(Modifier.fillMaxWidth()
                .studioFocusRing(RoundedCornerShape(StudioTokens.radiusMd), background = colors.canvas)
                .clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = StudioTokens.controlMinHeight)
                .padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                WorkFoldChevron(expanded, "chevron-right", 90f)
                StudioIcon(if (kind == "thinking") "brain" else "wrench", Modifier.size(StudioTokens.iconSize), colors.faint)
                Muted(m[if (kind == "thinking") "work.thinking" else "work.toolActivity"])
                message.obj("data")?.string("name")?.let { Muted(it, modifier = Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                Muted(time)
            }
            StudioDisclosure(expanded) {
                Box(Modifier.padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1)) { ConversationMarkdown(text, selectable) }
            }
        }
        history -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            WorkDot(colors.faint)
            Muted(text, modifier = Modifier.weight(1f))
            Muted(time)
        }
        else -> Column(Modifier.fillMaxWidth().border(StudioTokens.borderWidth, colors.subtleBorder, RoundedCornerShape(StudioTokens.radiusMd))
            .background(colors.surface, RoundedCornerShape(StudioTokens.radiusMd)).padding(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                StudioIcon("bot", Modifier.size(StudioTokens.iconSize), colors.faint)
                Text(name ?: m.status(role ?: "assistant"), Modifier.weight(1f), fontSize = StudioTokens.textSmall, color = colors.muted, fontWeight = FontWeight.Medium)
                Muted(time)
            }
            ConversationMarkdown(text, selectable)
        }
    }
}
