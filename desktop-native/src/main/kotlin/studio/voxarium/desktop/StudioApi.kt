package studio.voxarium.desktop

import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val ORIGIN = "http://127.0.0.1:8765"
internal val studioJson = Json { ignoreUnknownKeys = true }

internal data class StudioEvent(val type: String, val payload: JsonObject? = null)

@Serializable
internal data class BuildIdentity(val version: String, val display: String, val installerVersion: String = version)

@Serializable
internal data class Health(
    val service: String,
    val status: String,
    val version: String,
    @SerialName("desktop_instance") val desktopInstance: String? = null,
)

@Serializable
internal data class ProjectBinding(
    val root: String,
    val name: String,
    @SerialName("project_id") val id: String,
    val epoch: String,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]+")) && epoch.isNotBlank())
    }
}

@Serializable
internal data class Page(
    val path: String,
    val query: Selectors = Selectors(),
)

@Serializable
internal data class Selectors(
    val path: String? = null,
    val task: String? = null,
    val chat: String? = null,
    val assign: String? = null,
)

@Serializable
internal data class SavedTab(val root: String, val page: Page)

@Serializable
internal data class Geometry(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean = false)

@Serializable
internal data class SavedWindow(
    val tabs: List<SavedTab>,
    @SerialName("active_tab") val activeTab: Int?,
    val geometry: Geometry? = null,
)

@Serializable
internal data class Session(
    val version: Int,
    val windows: List<SavedWindow>,
    @SerialName("active_window") val activeWindow: Int?,
)

internal class ApiFailure(val status: Int, message: String) : IOException(message)

internal fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

internal fun query(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&", prefix = "?") { (key, value) -> "${segment(key)}=${segment(value)}" }

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonElement.objects(): List<JsonObject> = jsonArray.map { it.jsonObject }
internal fun jsonFields(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) -> put(key, value) }
}

internal class StudioApi(
    private val token: String? = System.getenv("VOXARIUM_STUDIO_TOKEN")?.takeIf(String::isNotBlank),
) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        // Uvicorn serves HTTP/1.1. An h2c upgrade can consume the first request's body.
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun project(binding: ProjectBinding) = ProjectApi(this, binding)

    // Health is unauthenticated. Never send a token to an unverified listener on 8765 (§2.7).
    suspend fun health(): Health {
        val body = request("/healthz", authenticated = false)
        return studioJson.decodeFromJsonElement(Health.serializer(), body)
    }

    suspend fun open(root: String, create: Boolean = false): ProjectBinding {
        val body = buildJsonObject {
            put("path", root)
            put("create", create)
        }
        return studioJson.decodeFromJsonElement(ProjectBinding.serializer(), request("/api/projects/open", "POST", body))
    }

    suspend fun loadSession(): Session? {
        val body = try {
            request("/api/desktop/session")
        } catch (error: ApiFailure) {
            if (error.status == 404) return null
            throw error
        }
        return studioJson.decodeFromJsonElement(Session.serializer(), body)
    }

    suspend fun saveSession(session: Session) {
        request("/api/desktop/session", "PUT", studioJson.encodeToJsonElement(Session.serializer(), session))
    }

    suspend fun request(
        path: String,
        method: String = "GET",
        body: JsonElement? = null,
        authenticated: Boolean = true,
        timeout: Duration = Duration.ofSeconds(20),
    ): JsonElement = runInterruptible(Dispatchers.IO) {
        val builder = requestBuilder(path, "application/json", authenticated, timeout)
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else {
            builder.header("Content-Type", "application/json")
            HttpRequest.BodyPublishers.ofString(body.toString())
        }
        val response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw apiFailure(response.statusCode(), response.body())
        if (response.body().isEmpty()) JsonNull else studioJson.parseToJsonElement(response.body())
    }

    suspend fun uploadAttachment(path: String, name: String, data: ByteArray): JsonObject = runInterruptible(Dispatchers.IO) {
        val boundary = "studio-${UUID.randomUUID()}"
        val safeName = name.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")
        val header = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val multipart = HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofString(header),
            HttpRequest.BodyPublishers.ofByteArray(data),
            HttpRequest.BodyPublishers.ofString("\r\n--$boundary--\r\n"),
        )
        val request = requestBuilder(path, "application/json", authenticated = true)
            .header("Content-Type", "multipart/form-data; boundary=$boundary").POST(multipart).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw apiFailure(response.statusCode(), response.body())
        studioJson.parseToJsonElement(response.body()).jsonObject
    }

    suspend fun downloadAttachment(path: String, maxBytes: Int): ByteArray = runInterruptible(Dispatchers.IO) {
        val request = requestBuilder(path, "*/*", authenticated = true).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { stream ->
            if (response.statusCode() !in 200..299) {
                throw apiFailure(response.statusCode(), stream.readNBytes(64 * 1024).decodeToString())
            }
            stream.readNBytes(maxBytes + 1).also { if (it.size > maxBytes) throw AttachmentTooLarge() }
        }
    }

    fun postFrames(path: String, body: JsonObject) = flow {
        val request = requestBuilder(path, "application/x-ndjson", authenticated = true)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = runInterruptible(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
        response.body().use { stream ->
            if (response.statusCode() !in 200..299) {
                val text = runInterruptible(Dispatchers.IO) { stream.readNBytes(64 * 1024).decodeToString() }
                throw apiFailure(response.statusCode(), text)
            }
            val mediaType = response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim()
            if (mediaType != "application/x-ndjson") throw IOException("Expected application/x-ndjson")
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = runInterruptible(Dispatchers.IO) { reader.readLine() } ?: break
                    if (line.isNotBlank()) emit(studioJson.parseToJsonElement(line).jsonObject)
                }
            }
        }
    }

    // §16: an empty first frame means the listener is connected before subscribing.
    fun remoteEvents() = flow {
        val request = requestBuilder("$nativeAiAccess/events", "text/event-stream", authenticated = true).GET().build()
        val response = runInterruptible(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
        response.body().use { stream ->
            if (response.statusCode() != 200) throw ApiFailure(response.statusCode(), "Remote event stream unavailable")
            if (response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim() != "text/event-stream") remoteInvalid()
            val decoder = Charsets.UTF_8.newDecoder()
            InputStreamReader(stream, decoder).buffered().use { reader ->
                emit(null)
                var kind = ""
                val data = StringBuilder()
                while (true) {
                    val line = try {
                        withTimeout(30_000) { runInterruptible(Dispatchers.IO) { reader.readLine() } }
                    } catch (_: TimeoutCancellationException) {
                        throw IOException("Remote event stream timed out")
                    } ?: break
                    if (line.length > 128 * 1024) remoteInvalid()
                    when {
                        line.startsWith("event:") -> kind = line.substringAfter(':').trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substringAfter(':').removePrefix(" "))
                            if (data.length > 128 * 1024) remoteInvalid()
                        }
                        line.isEmpty() -> {
                            if (kind == "remote" || kind == "resync_required") {
                                emit(StudioEvent(kind, studioJson.parseToJsonElement(data.toString()).jsonObject))
                            }
                            kind = ""
                            data.clear()
                        }
                    }
                }
            }
        }
    }

    private fun requestBuilder(path: String, accept: String, authenticated: Boolean,
                               timeout: Duration = Duration.ofSeconds(20)): HttpRequest.Builder {
        require(path == "/healthz" || path.startsWith("/api/"))
        val builder = HttpRequest.newBuilder(URI.create(ORIGIN + path))
            .timeout(timeout)
            .header("Accept", accept)
        if (authenticated && token != null) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun apiFailure(status: Int, body: String): ApiFailure {
        val error = runCatching { studioJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val detail = error?.get("detail")
        val message = (detail as? JsonPrimitive)?.contentOrNull
            ?: (detail as? JsonObject)?.let { it.string("message") ?: it.string("code") }
            ?: error?.string("code")
        return ApiFailure(status, message ?: "HTTP $status")
    }

    // §11.2: refresh from durable data when a scoped frame arrives or the stream is reconnected.
    fun events(binding: ProjectBinding) = socketFrames("/ws/projects/${binding.id}").mapNotNull { frame ->
        if (frame == null) return@mapNotNull StudioEvent("system.connected")
        if (frame.string("project_id") != binding.id || frame.string("epoch") != binding.epoch) return@mapNotNull null
        frame.string("type")?.let { StudioEvent(it, frame.obj("data")) }
    }

    fun desktopFrames() = socketFrames("/ws/desktop").filterNotNull()

    private fun socketFrames(path: String) = callbackFlow<JsonObject?> {
        val builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        if (token != null) builder.header("Authorization", "Bearer $token")
        val socket = builder.buildAsync(URI.create("ws://127.0.0.1:8765$path"),
            object : WebSocket.Listener {
                private val frame = StringBuilder()

                override fun onOpen(ws: WebSocket) {
                    trySend(null)
                    ws.request(1)
                }

                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    if (frame.length + data.length > 4 * 1024 * 1024) {
                        ws.abort()
                        close()
                        return null
                    }
                    frame.append(data)
                    if (last) {
                        val event = runCatching { studioJson.parseToJsonElement(frame.toString()).jsonObject }.getOrNull()
                        frame.clear()
                        if (event != null && !trySend(event).isSuccess) {
                            ws.abort()
                            close()
                            return null
                        }
                    }
                    ws.request(1)
                    return null
                }

                override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    close()
                    return null
                }

                override fun onError(ws: WebSocket, error: Throwable) {
                    close(error)
                }
            })
        socket.whenComplete { _, error -> if (error != null) close(error) }
        awaitClose { socket.thenAccept(WebSocket::abort) }
    }
}

// §5.3.2: native surfaces cannot rebind to the browser's mutable project adapter.
internal class ProjectApi(private val studio: StudioApi, val binding: ProjectBinding) {
    private val prefix = "/api/projects/${binding.id}"

    suspend fun get(path: String): JsonElement = studio.request(prefix + path)
    suspend fun get(path: String, timeout: Duration): JsonElement = studio.request(prefix + path, timeout = timeout)
    suspend fun post(path: String, body: JsonObject = JsonObject(emptyMap()), timeout: Duration = Duration.ofSeconds(20)): JsonElement =
        studio.request(prefix + path, "POST", body, timeout = timeout)
    fun postFrames(path: String, body: JsonObject) = studio.postFrames(prefix + path, body)
    suspend fun patch(path: String, body: JsonObject): JsonElement =
        studio.request(prefix + path, "PATCH", body)
    suspend fun put(path: String, body: JsonObject): JsonElement =
        studio.request(prefix + path, "PUT", body)
    suspend fun delete(path: String): JsonElement = studio.request(prefix + path, "DELETE")
    suspend fun uploadAttachment(path: String, name: String, data: ByteArray): JsonObject =
        studio.uploadAttachment(prefix + path, name, data)
    suspend fun downloadAttachment(path: String, maxBytes: Int): ByteArray = studio.downloadAttachment(prefix + path, maxBytes)

    fun web(section: String, search: String = ""): URI {
        require(section.startsWith('/') && !section.startsWith("//"))
        return URI.create("$ORIGIN/p/${binding.id}$section$search")
    }
}
