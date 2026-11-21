package studio.voxarium.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal data class ModelCamera(val yaw: Float = .60f, val pitch: Float = .40f, val zoom: Float = 1f, val pan: Offset = Offset.Zero)

internal data class ModelBounds(val centerX: Double, val centerY: Double, val centerZ: Double, val span: Double)

internal fun PreviewModel.visibleTriangles(hidden: Set<Int>): List<IntRange> = if (objects.isEmpty()) {
    listOf(0 until count)
} else {
    objects.filterNot { it.id in hidden }.map { it.first until it.first + it.count }
}

internal suspend fun PreviewModel.visibleBounds(ranges: List<IntRange>): ModelBounds? {
    if (ranges.isEmpty()) return null
    val context = currentCoroutineContext()
    val low = DoubleArray(3) { Double.POSITIVE_INFINITY }
    val high = DoubleArray(3) { Double.NEGATIVE_INFINITY }
    for (range in ranges) {
        for (triangle in range) {
            if (triangle % 1024 == 0) context.ensureActive()
            val first = triangle * 9
            for (i in first until first + 9) {
                val axis = i % 3
                val value = positions[i].toDouble()
                low[axis] = minOf(low[axis], value)
                high[axis] = maxOf(high[axis], value)
            }
        }
    }
    val span = low.indices.maxOf { high[it] - low[it] }
    if (!span.isFinite() || span <= 0.0) return null
    return ModelBounds((low[0] + high[0]) / 2, (low[1] + high[1]) / 2, (low[2] + high[2]) / 2, span)
}

internal suspend fun PreviewModel.renderPreview(camera: ModelCamera, size: IntSize, bounds: ModelBounds,
                                                ranges: List<IntRange>): ImageBitmap {
    val context = currentCoroutineContext()
    val scale = minOf(1f, 900f / maxOf(size.width, size.height))
    val width = (size.width * scale).toInt().coerceAtLeast(1)
    val height = (size.height * scale).toInt().coerceAtLeast(1)
    val pixels = ByteArray(width * height * 4)
    val depth = FloatArray(width * height) { Float.NEGATIVE_INFINITY }
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)

    val fit = (minOf(width, height) * .75f * camera.zoom / bounds.span)
    val screenX = width / 2f + camera.pan.x * scale
    val screenY = height / 2f + camera.pan.y * scale
    val sy = sin(camera.yaw)
    val cy = cos(camera.yaw)
    val sp = sin(camera.pitch)
    val cp = cos(camera.pitch)
    val x = FloatArray(3)
    val y = FloatArray(3)
    val z = FloatArray(3)
    val light = FloatArray(3)
    val groupTexture = if (textures.isEmpty()) null else IntArray(count) { -1 }.also { ids ->
        groups.forEach { group -> ids.fill(group.texture, group.first, group.first + group.count) }
    }

    for (range in ranges) for (triangle in range) {
        if (triangle % 1024 == 0) context.ensureActive()
        val position = triangle * 9
        for (corner in 0..2) {
            val base = position + corner * 3
            val px = ((positions[base] - bounds.centerX) * fit).toFloat()
            val py = ((positions[base + 1] - bounds.centerY) * fit).toFloat()
            val pz = ((positions[base + 2] - bounds.centerZ) * fit).toFloat()
            val front = px * sy + pz * cy
            x[corner] = screenX + px * cy - pz * sy
            y[corner] = screenY - py * cp + front * sp
            z[corner] = py * sp + front * cp

            val nx = normals[base]
            val ny = normals[base + 1]
            val nz = normals[base + 2]
            val normalFront = nx * sy + nz * cy
            val normalX = nx * cy - nz * sy
            val normalY = ny * cp - normalFront * sp
            val normalZ = ny * sp + normalFront * cp
            val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-8f)
            light[corner] = .35f + .65f * abs((normalX * -.25f + normalY * .45f + normalZ * .86f) / length).coerceIn(0f, 1f)
        }

        val dx1 = x[1] - x[0]
        val dx2 = x[2] - x[0]
        val dy1 = y[1] - y[0]
        val dy2 = y[2] - y[0]
        val area = dx1 * dy2 - dx2 * dy1
        if (!area.isFinite() || abs(area) < 1e-6f) continue
        val left = floor(x.min()).toInt().coerceAtLeast(0)
        val right = ceil(x.max()).toInt().coerceAtMost(width - 1)
        val top = floor(y.min()).toInt().coerceAtLeast(0)
        val bottom = ceil(y.max()).toInt().coerceAtMost(height - 1)
        val texture = if (groupTexture == null) null else textures[groupTexture[triangle]]
        val usesTexture = texture != null && uv != null
        val textureOffset = triangle * 6
        val red = colors?.get(position)?.toInt()?.and(255) ?: 184
        val green = colors?.get(position + 1)?.toInt()?.and(255) ?: 191
        val blue = colors?.get(position + 2)?.toInt()?.and(255) ?: 209
        for (row in top..bottom) {
            if (row % 64 == 0) context.ensureActive()
            val offsetY = row + .5f - y[0]
            for (column in left..right) {
                val offsetX = column + .5f - x[0]
                val b = (offsetX * dy2 - offsetY * dx2) / area
                val c = (dx1 * offsetY - dy1 * offsetX) / area
                val a = 1f - b - c
                if (a < 0f || b < 0f || c < 0f) continue
                val pixel = row * width + column
                val distance = a * z[0] + b * z[1] + c * z[2]
                if (distance <= depth[pixel]) continue
                depth[pixel] = distance
                val lighting = a * light[0] + b * light[1] + c * light[2]
                val output = pixel * 4
                if (usesTexture) {
                    val coords = requireNotNull(uv)
                    val sample = requireNotNull(texture)
                    val u = a * coords[textureOffset] + b * coords[textureOffset + 2] + c * coords[textureOffset + 4]
                    val v = a * coords[textureOffset + 1] + b * coords[textureOffset + 3] + c * coords[textureOffset + 5]
                    val texel = sample.texel(u, v)
                    pixels[output] = (red * (sample.pixels[texel].toInt() and 255) * lighting / 255f).toInt().toByte()
                    pixels[output + 1] = (green * (sample.pixels[texel + 1].toInt() and 255) * lighting / 255f).toInt().toByte()
                    pixels[output + 2] = (blue * (sample.pixels[texel + 2].toInt() and 255) * lighting / 255f).toInt().toByte()
                } else {
                    pixels[output] = (red * lighting).toInt().toByte()
                    pixels[output + 1] = (green * lighting).toInt().toByte()
                    pixels[output + 2] = (blue * lighting).toInt().toByte()
                }
                pixels[output + 3] = 255.toByte()
            }
        }
    }
    return Image.makeRaster(info, pixels, width * 4).use { it.toComposeImageBitmap() }
}

private fun PreviewTexture.texel(u: Float, v: Float): Int {
    fun coordinate(value: Float, size: Int, wrap: Int): Int {
        val t = when (wrap) {
            33071 -> value.coerceIn(0f, 1f)
            33648 -> 1f - abs(value.mod(2f) - 1f)
            else -> value.mod(1f)
        }
        return (t * size).toInt().coerceIn(0, size - 1)
    }
    return (coordinate(v, height, wrapT) * width + coordinate(u, width, wrapS)) * 4
}
