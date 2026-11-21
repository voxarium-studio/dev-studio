package studio.voxarium.desktop

import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

private val modelDecoders = listOf("decode.mjs", "MeshPreview.mjs", "FbxPreview.mjs", "VoxPreview.mjs")
private const val MAX_TEXTURE_BYTES = 64 * 1024 * 1024

internal data class PreviewModelObject(val id: Int, val name: String, val hidden: Boolean, val first: Int, val count: Int)
internal data class PreviewModelGroup(val first: Int, val count: Int, val texture: Int)
internal data class PreviewTexture(val width: Int, val height: Int, val pixels: ByteArray, val wrapS: Int, val wrapT: Int)
internal data class PreviewModel(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: ByteArray?,
    val uv: FloatArray?,
    val groups: List<PreviewModelGroup>,
    val objects: List<PreviewModelObject>,
    val textures: Map<Int, PreviewTexture>,
    val warnings: List<String>,
    val omittedTextures: List<String>,
    val truncated: Boolean,
) {
    val count get() = positions.size / 9
}

internal val nativeModelFormats = mapOf(
    ("mesh_preview" to "mesh-model") to setOf("glb", "gltf", "stl", "obj"),
    ("fbx_preview" to "fbx-model") to setOf("fbx"),
    ("vox_preview" to "vox-model") to setOf("vox", "voxarium"),
)

private fun nodeExecutable(): String {
    val resources = System.getProperty("compose.application.resources.dir")?.let(Path::of)
    val packaged = resources?.resolve("studio-backend/node/node.exe")
    return if (packaged?.isRegularFile() == true) packaged.toString() else "node.exe"
}

/** Built-in readers receive file content only and run outside the desktop process (§6.2). */
internal suspend fun decodePreviewModel(bytes: ByteArray, extension: String): PreviewModel {
    require(nativeModelFormats.values.any { extension in it })
    val directory = Files.createTempDirectory("voxarium-model-")
    try {
        for (name in modelDecoders) {
            val resource = requireNotNull(PreviewModel::class.java.getResourceAsStream("/native-model/$name"))
            resource.use { Files.copy(it, directory.resolve(name)) }
        }
        return runModelDecoder(directory, bytes, extension)
    } finally {
        for (name in modelDecoders) runCatching { Files.deleteIfExists(directory.resolve(name)) }
        runCatching { Files.deleteIfExists(directory) }
    }
}

private suspend fun runModelDecoder(directory: Path, bytes: ByteArray, extension: String): PreviewModel = coroutineScope {
    val process = runInterruptible(Dispatchers.IO) {
        ProcessBuilder(nodeExecutable(), "--max-old-space-size=512", directory.resolve("decode.mjs").toString(), extension)
            .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                val necessary = setOf("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH", "PATHEXT")
                environment().keys.removeIf { it.uppercase() !in necessary }
            }.start()
    }
    try {
        val writer = async(Dispatchers.IO) { process.outputStream.use { it.write(bytes) } }
        val reader = async(Dispatchers.IO) { process.inputStream.buffered().use(::readPreviewModel) }
        withTimeoutOrNull(30_000) {
            val result = reader.await()
            writer.await()
            val exit = runInterruptible(Dispatchers.IO) { process.waitFor() }
            if (exit != 0) throw IOException("Model reader exited with code $exit")
            result
        } ?: throw IOException("Model reader timed out")
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

private fun InputStream.previewBytes(count: Int): ByteArray = readNBytes(count).also {
    if (it.size != count) throw EOFException("Incomplete model data")
}

private fun InputStream.previewFloats(count: Int): FloatArray = FloatArray(count).also {
    ByteBuffer.wrap(previewBytes(count * 4)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
}

private fun readPreviewModel(stream: InputStream): PreviewModel {
    val length = ByteBuffer.wrap(stream.previewBytes(4)).order(ByteOrder.LITTLE_ENDIAN).int
    require(length in 1..1024 * 1024)
    val header = studioJson.parseToJsonElement(stream.previewBytes(length).decodeToString()).jsonObject
    header.string("error")?.let { throw IOException(it) }
    val triangles = requireNotNull(header.string("triangles")?.toIntOrNull())
    require(triangles in 1..800_000)
    val positions = stream.previewFloats(triangles * 9)
    val normals = stream.previewFloats(triangles * 9)
    val colors = if (header.flag("colors")) stream.previewBytes(triangles * 9) else null
    val uv = if (header.flag("uvs")) stream.previewFloats(triangles * 6) else null
    val objects = header.objects("objects").map { entry ->
        PreviewModelObject(entry.string("index")!!.toInt(), entry.string("name").orEmpty(), entry.flag("hidden"),
            entry.string("first")!!.toInt() / 3, entry.string("count")!!.toInt() / 3)
    }
    val groups = header.objects("groups").map { entry ->
        PreviewModelGroup(entry.string("start")!!.toInt() / 3, entry.string("count")!!.toInt() / 3,
            entry.string("texture")?.toIntOrNull() ?: -1)
    }
    val textures = mutableMapOf<Int, PreviewTexture>()
    val omitted = mutableListOf<String>()
    var encodedBytes = 0L
    var decodedBytes = 0L
    for (entry in header.objects("textures")) {
        val size = entry.string("length")!!.toInt()
        encodedBytes += size
        require(size > 0 && encodedBytes <= MAX_TEXTURE_BYTES)
        val bytes = stream.previewBytes(size)
        val texture = runCatching {
            val wrapS = entry.string("wrapS")?.toIntOrNull() ?: 10497
            val wrapT = entry.string("wrapT")?.toIntOrNull() ?: 10497
            decodePreviewTexture(bytes, MAX_TEXTURE_BYTES - decodedBytes, wrapS, wrapT)
        }.getOrNull()
        if (texture != null) {
            decodedBytes += texture.pixels.size
            textures[entry.string("id")!!.toInt()] = texture
        } else {
            omitted += entry.string("name").orEmpty()
        }
    }
    return PreviewModel(positions, normals, colors, uv, groups, objects, textures, header.stringList("warnings"), omitted,
        header.flag("truncated"))
}

private fun decodePreviewTexture(bytes: ByteArray, remaining: Long, wrapS: Int, wrapT: Int): PreviewTexture =
    Image.makeFromEncoded(bytes).use { image ->
        require(image.width > 0 && image.height > 0 && image.width.toLong() * image.height * 4 <= remaining)
        val info = ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        Bitmap().use { bitmap ->
            check(bitmap.allocPixels(info) && image.readPixels(bitmap))
            PreviewTexture(image.width, image.height, requireNotNull(bitmap.readPixels(info, image.width * 4, 0, 0)), wrapS, wrapT)
        }
    }
