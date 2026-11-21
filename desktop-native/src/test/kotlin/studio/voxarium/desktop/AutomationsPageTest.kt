package studio.voxarium.desktop

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationsPageTest {
    @Test
    fun fileTriggersRemainProjectRelativeAndRequireChangeKinds() {
        val draft = AutomationDraft(null)
        draft.setCategory("directory")
        for (path in listOf("../sibling", "C:\\Assets", "\\\\server\\share", "/absolute")) {
            draft.setTrigger("path", JsonPrimitive(path))
            assertEquals("pathOutside", draft.validate(), path)
        }
        draft.setTrigger("path", JsonPrimitive(" ./Models\\Characters/ "))
        assertNull(draft.validate())
        assertEquals("Models/Characters", draft.input().obj("trigger")?.string("path"))
        draft.setTrigger("changes", JsonArray(emptyList()))
        assertEquals("noChanges", draft.validate())
    }

    @Test
    fun switchingCategoriesRestoresTheEventAndItsFields() {
        val draft = AutomationDraft(null)
        draft.setTriggerKind("task.created")
        draft.setCategory("task")
        assertEquals("task.created", draft.trigger.string("kind"))
        draft.setCategory("directory")
        draft.setTrigger("path", JsonPrimitive("./Export/"))
        draft.setCategory("pipeline")
        draft.setCategory("task")
        assertEquals("task.created", draft.trigger.string("kind"))
        draft.setCategory("directory")
        assertEquals("./Export/", draft.trigger.string("path"))
        assertEquals("Export", draft.input().obj("trigger")?.string("path"))
    }

    @Test
    fun editingAKnownRulePreservesMissingPipelineAndDoesNotSendReadOnlyFields() {
        val stored = studioJson.parseToJsonElement("""
            {
                "id":"hand-authored",
                "name":"Watch models",
                "enabled":false,
                "trigger":{"kind":"path.changed","path":"Models","pattern":"*.fbx","changes":["modified"]},
                "action":{"kind":"pipeline.run","pipeline_id":"removed-pipeline","use_cache":false},
                "problem":"No pipeline"
            }
            """.trimIndent()).jsonObject
        val draft = AutomationDraft(stored)
        assertFalse(draft.changed)
        assertEquals("./Models/", draft.trigger.string("path"))
        draft.name = TextFieldValue(" Changed ")
        assertTrue(draft.changed)
        assertNull(draft.validate())
        val payload = draft.input()
        assertEquals(setOf("name", "enabled", "trigger", "action"), payload.keys)
        assertEquals("Changed", payload.string("name"))
        assertFalse(payload.flag("enabled"))
        assertEquals(stored.obj("action"), payload.obj("action"))
        assertEquals(stored.obj("trigger"), payload.obj("trigger"))
    }
}
