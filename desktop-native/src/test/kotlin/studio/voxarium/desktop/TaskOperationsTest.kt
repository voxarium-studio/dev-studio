package studio.voxarium.desktop

import androidx.compose.ui.text.input.TextFieldValue
import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

class TaskOperationsTest {
    @Test
    fun editingTheBriefDoesNotRewriteAssignmentOrDependencies() {
        val task = studioJson.parseToJsonElement("""
            {"title":"Existing","brief":"Original","assignee_id":"person","depends_on":["other"],"write_paths":["some path"]}
        """.trimIndent()).jsonObject
        val draft = TaskEditDraft(task)
        assertFalse(draft.dirty)
        draft.brief = TextFieldValue("Updated\n")
        assertEquals(jsonFields("brief" to "Updated\n"), draft.patch())
        draft.writePaths = TextFieldValue(" dir/*\n\n dir/*\njob.txt")
        assertEquals(listOf("dir/*", "job.txt"), draft.patch().stringList("write_paths"))
    }

    @Test
    fun briefEditsOnlySendUploadReferencesWhenAttachmentsChange() {
        val folder = "upload_${"a".repeat(32)}"
        val draft = TaskEditDraft(studioJson.parseToJsonElement("""
            {"title":"Existing","attachments":[{"reference":"$folder/one.png","name":"one.png"}]}
        """.trimIndent()).jsonObject)
        assertFalse(draft.dirty)
        draft.attachments = draft.attachments + TaskAttachment("two files.zip", folder)
        assertEquals(listOf("$folder/one.png", "$folder/two files.zip"), draft.patch().stringList("attachments"))
        draft.attachments = emptyList()
        assertEquals(JsonArray(emptyList()), draft.patch()["attachments"])
    }

    @Test
    fun attachmentMetadataCannotChangeTheRequestedProjectPath() {
        val folder = "upload_${"a".repeat(32)}"
        val task = studioJson.parseToJsonElement("""
            {"attachments":[{"reference":"$folder/два #.png","name":"два #.png"},
              {"reference":"$folder/elsewhere.txt","name":"test.txt"},
              {"reference":"$folder/../asset.txt","name":"../asset.txt"}]}
        """.trimIndent()).jsonObject
        val brief = task.taskAttachments(brief = true).single()
        assertEquals("/attachments/$folder/%D0%B4%D0%B2%D0%B0%20%23.png", brief.endpoint(null))
        val review = task.taskAttachments(brief = false).first()
        assertEquals("два #.png", review.reference)
        assertEquals("/teams/t/tasks/t1/attachments/%D0%B4%D0%B2%D0%B0%20%23.png", review.endpoint("/teams/t/tasks/t1"))
    }

    @Test
    fun countdownSwitchesFromAccountResetToScheduledRestart() {
        val reset = Instant.parse("2026-11-21T00:00:00Z")
        val resume = reset.plusSeconds(120)
        assertEquals(TaskLimitCountdown("taskRuntime.resetsIn", 10), taskLimitCountdown(reset, resume, reset.minusSeconds(10)))
        assertEquals(TaskLimitCountdown("taskRuntime.resumesIn", 100), taskLimitCountdown(reset, resume, reset.plusSeconds(20)))
        assertEquals(TaskLimitCountdown("taskRuntime.resetPassed", null), taskLimitCountdown(reset, null, reset))
        assertNull(taskLimitCountdown(null, null, reset))
    }

    @Test
    fun onlyHttpsClaudeCodeSessionLinksCanBeOpened() {
        val link = "https://claude.ai/code/session_01test?environment=env_01test"
        assertEquals(link, taskRemoteControlLink(link)?.toString())
        assertNull(taskRemoteControlLink("http://claude.ai/code/session_01test"))
        assertNull(taskRemoteControlLink("https://claude.ai.evil.invalid/code/session_01test"))
        assertNull(taskRemoteControlLink("https://user@claude.ai/code/session_01test"))
        assertNull(taskRemoteControlLink("https://claude.ai/settings"))
    }
}
