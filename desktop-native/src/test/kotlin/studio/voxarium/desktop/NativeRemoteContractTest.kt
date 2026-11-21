package studio.voxarium.desktop

import kotlinx.serialization.json.*
import kotlin.test.*

class NativeRemoteContractTest {
    private val binding = ProjectBinding("D:/game", "Game", "local-project", "session")
    private val remoteProfile = "11111111-1111-4111-a111-111111111111"
    private val storage = "a".repeat(32)
    private val fingerprint = "b".repeat(64)

    private fun observation() = buildJsonObject {
        put("project_id", binding.id); put("epoch", binding.epoch); put("backend_epoch", "backend")
        put("binding_generation", 8); put("identity_revision", 2); put("config_fingerprint", fingerprint)
        put("persistent_id", "managed-game"); put("url", "https://example.com")
        put("profile_id", remoteProfile); put("space_instance_id", storage)
        put("enabled", false); put("state", "disabled"); put("active_rooms", 0); put("pending_writes", 0)
    }

    @Test
    fun bindingSnapshotRefusesAnotherProjectAndUsesCapturedEvidence() {
        val observation = observation()
        assertFailsWith<RemoteFailure> { remoteBindingSnapshot(observation, binding.copy(epoch = "other")) }
        val snapshot = remoteBindingSnapshot(observation, binding)
        val next = remoteBindingSnapshot(JsonObject(observation + ("binding_generation" to JsonPrimitive(9))), binding)
        assertFalse(snapshot.sameTarget(next))
        val disable = RemoteBindingIntent(snapshot, "disable", "managed-game", snapshot.url).prepareBody()
        assertEquals(remoteProfile, disable.string("profile_id"))
        assertEquals(fingerprint, disable.string("config_fingerprint"))
        assertEquals(2L, disable.remoteNumber("expected_revision"))
    }

    @Test
    fun legacyMigrationCarriesExactCatalogProofAndRevisionZero() {
        val legacy = remoteBindingSnapshot(JsonObject(observation() + ("identity_revision" to JsonNull)), binding)
        val migration = NativeMigrationSelection("migration-plan", remoteProfile, "managed-game", storage)
        val payload = RemoteBindingIntent(legacy, "migrate", "managed-game", legacy.url, migration = migration).prepareBody()
        assertEquals(0L, payload.remoteNumber("expected_revision"))
        assertEquals(storage, payload.string("catalog_instance_id"))
        assertEquals(migration.plan, payload.string("migration_plan_id"))
        assertEquals(migration.profile, payload.string("profile_id"))
    }

    @Test
    fun grantReviewRequiresStatusAndExplicitScopeForExistingWork() {
        val target = NativeAiTarget("server", "executor", "export", "managed-game", storage, 3)
        val initial = NativeGrantIntent(target, "device", "user", "team", "member", setOf("operate_existing", "run_chat", "view_status"),
            "created_by_this_grantee", emptyList(), 1L, null, null)
        assertFalse(initial.valid)
        val selected = initial.copy(scope = "selected_resources", resources = listOf("chat-1"))
        assertTrue(selected.valid)
        val request = selected.body(fingerprint, 5000L)
        assertEquals(3L, request.remoteNumber("expected_export_revision"))
        assertEquals(3_605_000L, request.remoteNumber("expires_at"))
        assertEquals(listOf("chat-1"), request.remoteStrings("resource_ids"))
        assertEquals("device", request.string("grantee_registration_id"))
        assertFalse(selected.copy(permissions = setOf("run_chat")).valid)
    }

    @Test
    fun consumerReadUsesPinnedIdentityAndRefusesExpiredOrUnpermittedReads() {
        val device = NativeAiRegistration("requester", remoteProfile, "server", "https://example.com", "user", "client", "Desk", "studio",
            enabled = false, hasToken = true, revision = 1)
        val target = NativeAiTarget("server", "executor", "export", "managed-game", storage, 3)
        val pin = RemoteConsumerTarget(target, "grant", 7, Long.MAX_VALUE, "Game", "Executor", "epoch-1",
            setOf("view_status"), setOf("team"))
        val request = consumerReadEnvelope(device, pin, "resource.list", JsonObject(emptyMap())).obj("request")!!
        assertEquals(target, request.obj("target")?.nativeAiTarget())
        assertEquals("epoch-1", request.string("expected_executor_epoch"))
        assertEquals(7L, request.remoteNumber("grant_revision"))
        val status = consumerReadEnvelope(device, pin, "stream.subscribe",
            jsonFields("resource_id" to "task-1", "topic" to "status")).obj("request")!!
        assertEquals("stream.subscribe", status.string("op"))
        assertEquals("status", status.obj("payload")?.string("topic"))
        assertFailsWith<RemoteFailure> { consumerReadEnvelope(device, pin, "stream.subscribe", jsonFields("topic" to "transcript")) }
        assertFailsWith<RemoteFailure> { consumerReadEnvelope(device, pin, "transcript.page", JsonObject(emptyMap())) }
        assertFailsWith<RemoteFailure> { consumerReadEnvelope(device, pin.copy(expires = 0L), "resource.list", JsonObject(emptyMap())) }
        assertFailsWith<RemoteFailure> { consumerReadEnvelope(device.copy(server = "other"), pin, "resource.list", JsonObject(emptyMap())) }
    }
}
