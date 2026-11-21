package studio.voxarium.desktop

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text

private const val HUNYUAN_SERVER_DEFAULT = "http://127.0.0.1:8081"
private val hunyuanDefaults = listOf(
    choice("default_output_format", "defaults.outputFormat", "stl", "glb", "obj"),
    number("default_resolution", "defaults.resolution", 64, 8, 200),
    PluginField("default_model_path", "defaults.checkpoint", "tencent/Hunyuan3D-2.1"),
    PluginField("default_mv_model_path", "defaults.mvCheckpoint", "tencent/Hunyuan3D-2mv"),
    number("default_steps", "defaults.steps", 30, 1, 100),
    number("default_guidance_scale", "defaults.guidance", 5.0, 0, 20),
    number("default_octree_resolution", "defaults.octree", 256, 64, 512),
    number("default_num_chunks", "defaults.numChunks", 8000, 1000, 200000),
    number("default_face_count", "defaults.faceCount", 40000, 1000, 500000, legacyKey = "hunyuan_face_count"),
    toggle("default_remove_background", "defaults.removeBackground", true, legacyKey = "hunyuan_remove_background"),
    toggle("default_texture", "defaults.texture", false),
    choice("default_texture_resolution", "defaults.textureResolution", "512", "768"),
    number("default_max_num_view", "defaults.bakeViews", 0, 0, 9),
)
private val hunyuanMode = choice("hunyuan_mode", "connection.mode", "server", "local")
private val hunyuanUrl = PluginField("hunyuan_server_url", "connection.serverUrl", HUNYUAN_SERVER_DEFAULT,
    placeholder = HUNYUAN_SERVER_DEFAULT)
private val hunyuanServer = listOf(
    hunyuanUrl,
    choice("hunyuan_transport", "connection.transport", "auto", "tasks", "sync"),
    number("hunyuan_poll_interval", "connection.pollInterval", 2.0, 0.5, 60),
    choice("hunyuan_image_path", "connection.imagePath", "auto", "always", "never"),
    PluginField("hunyuan_api_key_env", "connection.apiKeyEnv", placeholder = "HUNYUAN3D_API_KEY"),
    number("hunyuan_timeout", "connection.timeout", 1800, 30, 7200),
)
private val hunyuanLocal = listOf(
    choice("hunyuan_device", "connection.device", "cuda", "cpu", "mps"),
    PluginField("hunyuan_subfolder", "connection.subfolder"),
    PluginField("hunyuan_mv_subfolder", "connection.mvSubfolder"),
    PluginField("hunyuan_texgen_path", "connection.texgenPath"),
)
internal val hunyuanFields = hunyuanDefaults + hunyuanMode + hunyuanServer + hunyuanLocal

private val hunyuanHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
    .followRedirects(HttpClient.Redirect.NEVER).build()

// Dev spec §9.4: the Studio records defaults. Health belongs to the configured model server.
internal fun hunyuanHealthUri(raw: String): URI {
    val base = URI.create(raw.trim().trimEnd('/'))
    require(base.scheme?.lowercase() in setOf("http", "https") && base.host != null && base.userInfo == null &&
        base.rawQuery == null && base.rawFragment == null)
    return URI.create("$base/health")
}

private suspend fun hunyuanHealth(address: URI): JsonObject = runInterruptible(Dispatchers.IO) {
    val request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build()
    val response = hunyuanHttp.send(request, HttpResponse.BodyHandlers.ofInputStream())
    response.body().use { stream ->
        if (response.statusCode() !in 200..299) throw ApiFailure(response.statusCode(), "HTTP ${response.statusCode()}")
        val body = stream.readNBytes(262145)
        if (body.size > 262144) throw IOException("HTTP response exceeds 256 KiB")
        studioJson.parseToJsonElement(body.decodeToString()).jsonObject
    }
}

@Composable
internal fun HunyuanSettingsPage(api: ProjectApi, title: String, state: PluginSettingsState, words: PluginWords,
                                  retry: () -> Unit, save: () -> Unit) {
    val scope = rememberCoroutineScope()
    val m = localMessages.current
    var environment by remember(api) { mutableStateOf<JsonObject?>(null) }
    var health by remember(api) { mutableStateOf<Pair<URI, JsonObject>?>(null) }
    var healthFailure by remember(api) { mutableStateOf<Problem?>(null) }
    var checking by remember(api) { mutableStateOf(false) }
    val address = state.value(hunyuanUrl).text.let { runCatching { hunyuanHealthUri(it) }.getOrNull() }

    LaunchedEffect(api) {
        while (true) {
            try {
                environment = api.get("/plugins").objects().firstOrNull { it.string("id") == "hunyuan3d" }?.obj("env")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
            delay(3000)
        }
    }

    PluginSettingsLayout(title, state, words, retry) {
        LibraryCard {
            LibrarySectionTitle(words["defaults.title"])
            WorkExplanation(words["defaults.blurb"])
            PluginFields(hunyuanDefaults, state, words)
            PluginSaveRow(state, save)
        }
        LibraryCard {
            LibrarySectionTitle("Hunyuan3D")
            Muted(words["connection.blurb"])
            PluginFields(listOf(hunyuanMode), state, words)
            if (state.value(hunyuanMode).text == "server") {
                PluginFields(hunyuanServer, state, words)
                WorkAction(words[if (checking) "connection.checking" else "connection.check"], "refresh-cw",
                    enabled = !checking && address != null, busy = checking) {
                    val endpoint = address ?: return@WorkAction
                    checking = true
                    health = null
                    healthFailure = null
                    scope.launch {
                        try {
                            health = endpoint to hunyuanHealth(endpoint)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            healthFailure = error.problem()
                        } finally {
                            checking = false
                        }
                    }
                }
                if (address == null) Text(words["invalidServer"], color = localColors.current.warning)
                healthFailure?.let { ProblemText(it) }
                health?.takeIf { it.first == address }?.second?.let { HunyuanHealth(it, words, m.language) }
            } else {
                PluginFields(hunyuanLocal, state, words)
            }
            PluginSaveRow(state, save)
        }
        LibraryCard {
            LibrarySectionTitle(words["environment.title"])
            Muted(words["environment.blurb"])
            val env = environment
            val current = env?.string("state")?.takeIf { it in setOf("ready", "building", "pending", "failed") } ?: "unknown"
            Text(words.text("environment.status", "state" to words["environment.$current"]))
            if (current == "failed") {
                env?.string("error")?.let { SelectionContainer { Text(it, color = localColors.current.danger) } }
                Muted(words["environment.retry"])
            }
        }
    }
}

@Composable
private fun HunyuanHealth(health: JsonObject, words: PluginWords, language: String) {
    val format = remember(language) { NumberFormat.getNumberInstance(Locale.forLanguageTag(language)).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    } }
    val gpu = health.string("gpu_name") ?: health.string("device") ?: words["health.unknownDevice"]
    val vram = health.string("vram_mode") ?: "?"
    val views = health.string("default_max_num_view")?.toIntOrNull()?.takeIf { it > 0 }
    Muted(listOfNotNull("$gpu · vram_mode $vram", views?.let { words.text("bakeViews", "count" to it) }).joinToString(" · "))
    val used = health.string("vram_used_gb")?.toDoubleOrNull()
    val total = health.string("vram_total_gb")?.toDoubleOrNull()
    val reserved = health.string("torch_reserved_gb")?.toDoubleOrNull() ?: 0.0
    if (used != null && total != null) Muted(words.text("health.vram", "used" to format.format(used),
        "total" to format.format(total), "server" to format.format(reserved), "others" to format.format((used - reserved).coerceAtLeast(0.0))))
    if (health.flag("texture_available")) {
        Muted(words.text("texturesAvailable", "count" to (health.string("queue_length") ?: "0")))
    } else {
        Muted(words.text("health.texturesUnavailable", "reason" to (health.string("texture_unavailable_reason") ?: words["health.noReason"])))
    }
    when ((health["mv_available"] as? JsonPrimitive)?.booleanOrNull) {
        true -> {
            val available = words.text("health.mvAvailable", "model" to (health.string("default_shape_model") ?: "single"))
            val loaded = health.string("shape_model_on_gpu")?.let { words.text("health.onCard", "model" to it) }
            Muted(listOfNotNull(available, loaded).joinToString(" · "))
        }
        false -> Muted(words.text("health.mvUnavailable", "reason" to (health.string("mv_unavailable_reason") ?: words["health.noReason"])))
        null -> Unit
    }
}
