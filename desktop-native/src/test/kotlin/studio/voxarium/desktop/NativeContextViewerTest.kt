package studio.voxarium.desktop

import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class NativeContextViewerTest {
    @Test
    fun fragmentRequestsPreserveOwnerAndEncodeTheKey() {
        val task = NativeContextTarget.Task("team-a", "task-b")
        val chat = NativeContextTarget.Chat("chat-c")
        assertEquals("/teams/team-a/tasks/task-b/context/fragments/42?key=secret%20%2B%3D&view=studio",
            task.fragment("42", "secret +=", "studio"))
        assertEquals("/chats/chat-c/context/fragments/42?key=a%26b&view=auto", chat.fragment("42", "a&b", "auto"))
    }

    @Test
    fun groupsAggregateOnlyValidDistinctSources() {
        val inventory = studioJson.parseToJsonElement("""
            {"sources":[
                {"id":"0","category":"tools","tokens":3,"text":"preview","text_key":"full"},
                {"id":"1","category":"system","tokens":6},
                {"id":"2","category":"tools","tokens":4},
                {"id":"2","category":"tools","tokens":100},
                {"id":"3","category":"memory","tokens":-1},
                {"category":"system","tokens":100}
            ]}
        """.trimIndent()).jsonObject
        val groups = nativeContextGroups(inventory)
        assertEquals(listOf("tools", "system", "memory"), groups.map(NativeContextGroup::category))
        assertEquals(listOf(7L, 6L, 0L), groups.map(NativeContextGroup::tokens))
        assertEquals(listOf("0", "2"), groups.first().sources.map(NativeContextSource::id))
        assertEquals("full", groups.first().sources.first().key)
        assertEquals("preview", groups.first().sources.first().text)
    }
}
