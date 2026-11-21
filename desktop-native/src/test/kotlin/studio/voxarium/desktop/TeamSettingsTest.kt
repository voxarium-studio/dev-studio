package studio.voxarium.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamSettingsTest {
    @Test
    fun savesOnlyChangedTeamFieldsAndKeepsResetDistinctFromRemoval() {
        val settings = teamSettingsPatch(
            mapOf(
                TeamSettingField("max_turns") to JsonPrimitive(64),
                TeamSettingField("required_documents") to JsonArray(emptyList()),
                TeamSettingField("resume", prompt = true) to JsonNull,
                TeamSettingField("docs_location", prompt = true) to JsonPrimitive(""),
            ),
        )
        assertEquals(studioJson.parseToJsonElement("""
            {"teams":{"max_turns":64,"required_documents":[],"prompts":{"resume":null,"docs_location":""}}}
        """.trimIndent()), settings)
    }

    @Test
    fun enforcesProjectTurnBounds() {
        assertEquals(1, validTeamTurns("1"))
        assertEquals(1000, validTeamTurns("1000"))
        for (invalid in listOf("", "0", "-1", "1001", "1.5")) assertNull(validTeamTurns(invalid))
    }
}
