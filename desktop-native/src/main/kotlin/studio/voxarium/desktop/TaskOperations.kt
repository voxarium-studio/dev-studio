package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.serialization.json.*
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

internal val nativeTaskResults = listOf("done", "question", "error")

@Composable
internal fun TaskDiffPanel(api: ProjectApi?, endpoint: String, path: String, close: () -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    var diff by remember(endpoint, path) { mutableStateOf<String?>(null) }
    var problem by remember(endpoint, path) { mutableStateOf<Problem?>(null) }
    LaunchedEffect(api, endpoint, path) {
        if (api == null) return@LaunchedEffect
        try {
            diff = api.get("$endpoint/changes/patch" + query("path" to path)).jsonObject.string("patch").orEmpty()
        } catch (error: Exception) {
            problem = error.problem()
        }
    }
    Column(Modifier.fillMaxSize().padding(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Heading(m["taskActions.diff"], size = StudioTokens.titleSize.value.toInt())
                LibraryCaption(path, mono = true, maxLines = 2)
            }
            WorkAction(m["close"], "x", outlined = false, iconOnly = true, onClick = close)
        }
        Rule()
        val lines = remember(diff) { diff?.trimEnd()?.takeIf(String::isNotEmpty)?.lines().orEmpty() }
        if (lines.isEmpty()) {
            when {
                problem != null -> ProblemText(requireNotNull(problem))
                diff == null -> Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                    StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                    Muted(m["loading"])
                }
                else -> Muted(m["taskActions.emptyDiff"])
            }
        } else ScrollList(Modifier.weight(1f).fillMaxWidth()) {
            items(lines) { line ->
                Text(line, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall, color = when {
                    line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> colors.info
                    line.startsWith('+') -> colors.good
                    line.startsWith('-') -> colors.danger
                    else -> colors.text
                })
            }
        }
    }
}

// §10.4: only submit fields changed by this editor. The board may refresh while it is open.
internal class TaskEditDraft(private val original: JsonObject) {
    var title by mutableStateOf(TextFieldValue(original.string("title").orEmpty()))
    var brief by mutableStateOf(TextFieldValue(original.string("brief").orEmpty()))
    var assignee by mutableStateOf(original.string("assignee_id").orEmpty())
    var writePaths by mutableStateOf(TextFieldValue(original.stringList("write_paths").joinToString("\n")))
    var dependencies by mutableStateOf(original.stringList("depends_on").toSet())
    var autoStart by mutableStateOf(original.flag("auto_start"))
    private val originalAttachments = original.taskAttachments(brief = true)
    var attachments by mutableStateOf(originalAttachments)

    val dirty: Boolean get() = patch().isNotEmpty()

    fun patch(): JsonObject = buildJsonObject {
        if (title.text != original.string("title").orEmpty()) put("title", title.text.trim())
        if (brief.text != original.string("brief").orEmpty()) put("brief", brief.text)
        if (assignee != original.string("assignee_id").orEmpty()) put("assignee_id", assignee)
        if (autoStart != original.flag("auto_start")) put("auto_start", autoStart)
        if (writePaths.text != original.stringList("write_paths").joinToString("\n")) {
            put("write_paths", JsonArray(writePaths.text.lineSequence().map(String::trim)
                .filter(String::isNotEmpty).distinct().map(::JsonPrimitive).toList()))
        }
        if (dependencies != original.stringList("depends_on").toSet()) {
            put("depends_on", JsonArray(dependencies.map(::JsonPrimitive)))
        }
        if (attachments != originalAttachments) put("attachments", attachments.attachmentReferences())
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun TaskStatusPicker(tab: ProjectTab, task: JsonObject, endpoint: String) {
    val m = localMessages.current
    val status = task.string("status") ?: return
    val running = task.flag("is_running")
    StudioSelect(m["taskActions.status"], status, taskStatuses.associateWith(m::status),
        Modifier.widthIn(max = StudioTokens.menuMinWidth).workStatusHalo(running, null, StudioTokens.radiusMd),
        enabled = !tab.acting && !running) { next -> selectTaskStatus(tab, task, endpoint, m, next) }
}

internal fun selectTaskStatus(tab: ProjectTab, task: JsonObject, endpoint: String, m: Messages, next: String) {
    if (!tab.acting && !task.flag("is_running") && next != task.string("status")) {
        tab.action { patch(endpoint, jsonFields("status" to next, "summary_locale" to m.language)) }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun TaskEditForm(tab: ProjectTab, task: JsonObject, draft: TaskEditDraft, endpoint: String, taskId: String) {
    val m = localMessages.current
    val enabled = !tab.acting
    val team = tab.list?.teams?.firstOrNull { it.string("id") == task.string("team_id") }
    val members = team?.objects("members").orEmpty()
    val assignee = members.firstOrNull { it.string("id") == draft.assignee }
    val running = task.flag("is_running")
    val candidates = tab.list?.items.orEmpty().filter {
        it.id != taskId && it.payload.string("team_id") == task.string("team_id") &&
            (!it.payload.flag("archived") || it.id in draft.dependencies)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(StudioTokens.panePad),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        Heading(m["taskActions.edit"], size = StudioTokens.titleSize.value.toInt())
        tab.actionError?.let { ProblemText(it) }
        Muted(m["work.taskTitleLabel"])
        StudioField(m["work.taskTitleLabel"], draft.title, { draft.title = it }, Modifier.fillMaxWidth(), enabled = enabled)
        Muted(m["taskBrief"])
        TaskAttachmentPicker(tab, draft.attachments, { draft.attachments = it }) { paste ->
            TextArea(draft.brief, { draft.brief = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp)
                .then(paste).semantics { contentDescription = m["taskBrief"] }, enabled = enabled)
        }
        Muted(m["work.taskAssignTo"])
        StudioSelect(m["work.taskAssignTo"], draft.assignee, members.mapNotNull { member ->
            member.string("id")?.let { it to (member.string("name") ?: it) }
        }.toMap(), enabled = enabled && !running) { next ->
            draft.assignee = next
            if (members.firstOrNull { it.string("id") == next }?.string("kind") == "human") draft.autoStart = false
        }
        WorkCheckbox(m["work.taskAutoStart"], draft.autoStart, enabled && !running && assignee?.string("kind") == "agent") {
            draft.autoStart = it
        }
        if (!running) {
            Muted(m["taskActions.writePaths"])
            TextArea(draft.writePaths, { draft.writePaths = it }, Modifier.fillMaxWidth().heightIn(min = 68.dp, max = 120.dp)
                .semantics { contentDescription = m["taskActions.writePaths"] }, enabled = enabled)
            Muted(m["taskActions.writePathsHint"])
        }
        if (!running && candidates.isNotEmpty()) {
            Muted(m["taskActions.dependencies"])
            for (entry in candidates) {
                val label = listOfNotNull(entry.payload.string("display_id"), entry.title).joinToString(" · ")
                WorkCheckbox(label, entry.id in draft.dependencies, enabled) { checked ->
                    draft.dependencies = if (checked) draft.dependencies + entry.id else draft.dependencies - entry.id
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = StudioTokens.space1), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2, Alignment.End)) {
            WorkAction(m["cancel"], null, enabled = enabled) { tab.taskEdits.remove(taskId) }
            WorkAction(m["work.save"], "save", enabled = enabled && draft.dirty && draft.title.text.isNotBlank(), primary = true) {
                val body = buildJsonObject {
                    draft.patch().forEach { (name, value) -> put(name, value) }
                    put("summary_locale", m.language)
                }
                tab.action {
                    patch(endpoint, body)
                    tab.taskEdits.remove(taskId)
                }
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun TaskLifecycleActions(tab: ProjectTab, task: JsonObject, assignee: JsonObject?, endpoint: String,
                                  inline: Boolean, close: () -> Unit) {
    val m = localMessages.current
    val id = task.string("id").orEmpty()
    val status = task.string("status")
    val actor = task.string("assignee_id").takeIf { assignee?.string("kind") == "human" }
    val live = task.flag("is_running")
    val compressing = task.flag("is_compacting") || task.flag("is_compressing_context")
    val enabled = !tab.acting
    val canClaim = actor != null && status in setOf("todo", "pending", "blocked")
    val canComplete = actor != null && status == "in_progress"
    val canReview = status == "needs_review"
    val canMessage = actor == null && live && !compressing
    val taskItems = tab.list?.takeIf { Section.from(tab.page) == Section.TASKS }?.items
    val blocked = taskItems != null && task.stringList("depends_on").any { dependency ->
        taskItems.none { it.id == dependency && it.status == "done" }
    }
    val pendingRelease = actor == null && status in setOf("todo", "blocked") &&
        (task.string("producer_chat_id") == null || task.flag("producer_approved"))
    val commentKey = "task-action:$id"
    var comment by remember(id) { mutableStateOf(TextFieldValue(tab.drafts[commentKey].orEmpty())) }
    var result by remember(id) { mutableStateOf("done") }
    var confirmation by remember(id) { mutableStateOf<String?>(null) }
    val review = remember(tab, id) { tab.taskReviewDrafts[id] ?: TaskReviewDraft() }

    fun updateReview(attachments: List<TaskAttachment>) {
        review.attachments = attachments
        if (attachments.isEmpty()) tab.taskReviewDrafts.remove(id) else tab.taskReviewDrafts[id] = review
    }

    fun send(path: String, fields: JsonObject, clear: Boolean = false) {
        tab.action {
            if (path == "request-changes") review.submitted += review.attachments.map { it.name }
            post("$endpoint/$path", fields)
            if (path == "approve") {
                for (entry in review.attachments.filterNot { it.name in review.submitted }) {
                    try {
                        delete(requireNotNull(entry.endpoint(endpoint)))
                    } catch (_: IOException) {
                        // Approval succeeded even if cleanup of an unused upload failed.
                    }
                }
            }
            if (path == "approve" || path == "request-changes") updateReview(emptyList())
            if (clear) {
                comment = TextFieldValue()
                tab.drafts.remove(commentKey)
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        tab.actionError?.let { ProblemText(it) }
        if (assignee?.string("kind") == "agent") TaskResumeOnLimit(tab, task, endpoint)
        if (canMessage || canComplete || canReview) {
            val label = m[when {
                canMessage -> "message"
                canReview -> "taskActions.reviewComment"
                else -> "taskActions.summary"
            }]
            val input: @Composable (Modifier) -> Unit = { paste ->
                TextArea(comment, {
                    comment = it
                    if (it.text.isBlank()) tab.drafts.remove(commentKey) else tab.drafts[commentKey] = it.text
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 86.dp).then(paste)
                    .semantics { contentDescription = label }, enabled = enabled, placeholder = { Muted(label) })
            }
            if (canReview) {
                TaskAttachmentPicker(tab, review.attachments, ::updateReview, reviewEndpoint = endpoint, removeReview = { entry ->
                    tab.action {
                        if (entry.name !in review.submitted) {
                            try {
                                delete(requireNotNull(entry.endpoint(endpoint)))
                            } catch (error: ApiFailure) {
                                if (error.status != 404) throw error
                            }
                        }
                        updateReview(review.attachments - entry)
                    }
                }, body = input)
            } else input(Modifier)
        }
        if (blocked && (canClaim || status == "pending")) Muted(m["taskActions.blocked"])
        confirmation?.let { confirm ->
            val remove = confirm == "delete"
            Muted(m[if (remove) "taskActions.deleteConfirm" else "taskActions.resetConfirm"])
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                WorkAction(m["cancel"], null) { confirmation = null }
                WorkAction(m[if (remove) "work.delete" else "taskActions.reset"], null, enabled = enabled, danger = true) {
                    tab.action {
                        if (remove) delete(endpoint) else post("$endpoint/reset")
                        confirmation = null
                        tab.drafts.remove(commentKey)
                        tab.taskEdits.remove(id)
                        updateReview(emptyList())
                        comment = TextFieldValue()
                        if (remove) close()
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            when {
                canClaim -> WorkAction(m["taskActions.claim"], "play", enabled = enabled && !blocked, primary = true) {
                    send("claim", jsonFields("actor_id" to requireNotNull(actor)))
                }
                canComplete -> {
                    StudioSelect(m["taskActions.complete"], result,
                        nativeTaskResults.associateWith { m["taskActions.result.$it"] },
                        Modifier.widthIn(max = StudioTokens.menuMinWidth), enabled = enabled) { result = it }
                    WorkAction(m["taskActions.complete"], "send", enabled = enabled, primary = true) {
                        send("complete", jsonFields("actor_id" to requireNotNull(actor), "result_type" to result,
                            "summary" to comment.text.trim()), clear = true)
                    }
                }
                canReview -> {
                    WorkAction(m["taskActions.approve"], "check", enabled = enabled, primary = true) {
                        send("approve", JsonObject(emptyMap()), clear = true)
                    }
                    WorkAction(m["taskActions.requestChanges"], "corner-up-left", enabled = enabled && comment.text.isNotBlank()) {
                        send("request-changes", buildJsonObject {
                            put("comment", comment.text.trim())
                            put("summary_locale", m.language)
                            put("attachments", review.attachments.attachmentReferences())
                        }, clear = true)
                    }
                }
                pendingRelease -> WorkAction(m["queueTask"], "arrow-right", enabled = enabled) {
                    tab.action { patch(endpoint, jsonFields("status" to "pending", "summary_locale" to m.language)) }
                }
                status == "pending" && assignee?.string("kind") == "agent" && !live && !compressing -> {
                    WorkAction(m[if (task.string("agent_session_id") == null) "startTask" else "resume"], "play",
                        enabled = enabled && !blocked, primary = true) {
                        send("start", jsonFields("summary_locale" to m.language))
                    }
                }
            }
            if (canMessage) WorkAction(m["send"], "send", enabled = enabled && comment.text.isNotBlank(), primary = true) {
                send("notes", jsonFields("text" to comment.text.trim()), clear = true)
            }
            if (live && !compressing) WorkAction(m["cancelTask"], "square", enabled = enabled, danger = true) {
                send("cancel", JsonObject(emptyMap()))
            }
            if (!inline && !live && !compressing && confirmation == null) {
                val hasDialogue = task.objects("messages").any {
                    it.string("kind") in setOf("text", "note", "summary") && !it.string("text").isNullOrBlank()
                }
                if (assignee?.string("kind") == "agent" && hasDialogue) {
                    WorkAction(m["taskActions.compress"], "layers", enabled = enabled) { send("compress", JsonObject(emptyMap())) }
                }
                if (status == "done" || task.flag("archived")) {
                    val archived = task.flag("archived")
                    WorkAction(m[if (archived) "work.taskUnarchive" else "work.taskArchive"], "archive", enabled = enabled) {
                        tab.action { patch(endpoint, buildJsonObject { put("archived", !archived) }) }
                    }
                }
                WorkAction(m["taskActions.reset"], "repeat", enabled = enabled, iconOnly = true) { confirmation = "reset" }
                WorkAction(m["work.delete"], "trash-2", enabled = enabled, iconOnly = true) { confirmation = "delete" }
            }
        }
    }
}
