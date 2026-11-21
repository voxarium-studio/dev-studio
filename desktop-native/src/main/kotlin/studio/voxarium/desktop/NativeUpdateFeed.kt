package studio.voxarium.desktop

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

private const val maxInstallerBytes = 1024L * 1024 * 1024
private const val metadataBytes = 64 * 1024
private val archiveName = Regex("Voxarium-Studio-Setup-[a-zA-Z0-9._+-]+\\.exe")
private val updateRepository = Regex("([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?)/([a-zA-Z0-9_.-]{1,100})")
private val downloadHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

internal data class ReleaseAsset(val id: Long, val name: String, val bytes: Long)
internal data class NativeRelease(val tag: String, val page: URI, val assets: List<ReleaseAsset>)
internal data class InstallerManifest(val version: String, val path: String, val sha512: String)
internal data class DownloadedInstaller(val release: NativeRelease, val file: Path, val bytes: Long, val sha512: String)

// §12.1: read the scalar root fields emitted by the NSIS build's latest.yml. Nested
// entries under files are ignored, and the path must be one exact release asset name.
internal fun parseInstallerManifest(source: String, tag: String): InstallerManifest {
    val fields = mutableMapOf<String, String>()
    for (line in source.lineSequence()) {
        val match = Regex("^(version|path|sha512):[ \\t]*(\\S+)[ \\t]*$").matchEntire(line) ?: continue
        if (fields.put(match.groupValues[1], match.groupValues[2]) != null) invalidInstallerManifest()
    }
    val version = fields["version"] ?: invalidInstallerManifest()
    val path = fields["path"] ?: invalidInstallerManifest()
    val hash = fields["sha512"] ?: invalidInstallerManifest()
    val decoded = runCatching { Base64.getDecoder().decode(hash) }.getOrNull()
    if (version != tag.removePrefix("v") || stableReleaseVersion(version) == null || !archiveName.matches(path) ||
        decoded?.size != 64 || Base64.getEncoder().encodeToString(decoded) != hash) invalidInstallerManifest()
    return InstallerManifest(version, path, hash)
}

private fun invalidInstallerManifest(): Nothing = throw StartupFailure("nativeUpdate.invalidManifest")

internal fun releaseDownloadRedirect(value: URI): Boolean =
    value.scheme.equals("https", ignoreCase = true) && value.host?.lowercase(java.util.Locale.ROOT) in downloadHosts &&
        value.rawUserInfo == null && value.rawFragment == null && value.port in listOf(-1, 443)

internal fun copyVerifiedInstaller(
    input: InputStream, temporary: Path, expectedBytes: Long, expectedHash: String, progress: (Int) -> Unit,
) {
    require(expectedBytes in 1..maxInstallerBytes)
    val hash = MessageDigest.getInstance("SHA-512")
    val buffer = ByteArray(256 * 1024)
    var count = 0L
    var percent = 0
    Files.newOutputStream(temporary).use { output ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            if (count > expectedBytes) throw StartupFailure("nativeUpdate.invalidInstaller")
            hash.update(buffer, 0, read)
            output.write(buffer, 0, read)
            val next = (100 * count / expectedBytes).toInt().coerceAtMost(99)
            if (next != percent) {
                percent = next
                progress(next)
            }
        }
    }
    if (count != expectedBytes || Base64.getEncoder().encodeToString(hash.digest()) != expectedHash) {
        throw StartupFailure("nativeUpdate.invalidInstaller")
    }
}

internal class NativeUpdateFeed {
    private val repo = (System.getenv("VOXARIUM_STUDIO_UPDATE_REPO") ?: "voxarium-studio/dev-studio")
        .takeIf { updateRepository.matches(it) && it.substringAfter('/') !in setOf(".", "..") }
    private val privateFeed = System.getenv("VOXARIUM_STUDIO_UPDATE_PRIVATE") == "1"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    suspend fun latest(current: String): NativeRelease? = runInterruptible(Dispatchers.IO) {
        val repository = repo ?: throw StartupFailure("nativeUpdate.invalidFeed")
        val response = send(URI.create("https://api.github.com/repos/$repository/releases/latest"),
            "application/vnd.github+json", credentials())
        val release = response.body().use { body ->
            if (response.statusCode() != 200) throw StartupFailure("nativeUpdate.failed", "HTTP ${response.statusCode()}")
            studioJson.parseToJsonElement(body.readBounded(1024 * 1024)).jsonObject
        }
        if (release.flag("draft") || release.flag("prerelease")) return@runInterruptible null
        val tag = release.string("tag_name") ?: throw StartupFailure("nativeUpdate.failed")
        if (!newerStableRelease(current, tag)) return@runInterruptible null
        val assets = release.objects("assets").mapNotNull { asset ->
            val name = asset.string("name") ?: return@mapNotNull null
            val id = asset.string("id")?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val bytes = asset.string("size")?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            ReleaseAsset(id, name, bytes)
        }
        if (assets.none { archiveName.matches(it.name) }) return@runInterruptible null
        NativeRelease(tag, URI.create("https://github.com/$repository/releases/tag/${segment(tag)}"), assets)
    }

    suspend fun download(release: NativeRelease, root: Path, progress: (Int) -> Unit): DownloadedInstaller =
        runInterruptible(Dispatchers.IO) {
            val token = credentials()
            val metadata = release.assets.singleOrNull { it.name == "latest.yml" && it.bytes <= metadataBytes } ?: invalidInstallerManifest()
            val manifest = openAsset(metadata, token).use { parseInstallerManifest(it.readBounded(metadataBytes), release.tag) }
            val asset = release.assets.singleOrNull { it.name == manifest.path && it.bytes in 1..maxInstallerBytes }
                ?: invalidInstallerManifest()
            Files.createDirectories(root)
            val directory = Files.createTempDirectory(root, "release-")
            val partial = directory.resolve("installer.part")
            val installer = directory.resolve(asset.name)
            var verified = false
            try {
                openAsset(asset, token).use { copyVerifiedInstaller(it, partial, asset.bytes, manifest.sha512, progress) }
                Files.move(partial, installer, ATOMIC_MOVE)
                verified = true
                DownloadedInstaller(release, installer, asset.bytes, manifest.sha512)
            } finally {
                if (!verified) {
                    Files.deleteIfExists(partial)
                    Files.deleteIfExists(directory)
                }
            }
        }

    private fun openAsset(asset: ReleaseAsset, token: String?): InputStream {
        val repository = repo ?: throw StartupFailure("nativeUpdate.invalidFeed")
        val binary = "application/octet-stream"
        var response = send(URI.create("https://api.github.com/repos/$repository/releases/assets/${asset.id}"), binary, token)
        if (response.statusCode() == 302) {
            response.body().close()
            val redirect = response.headers().firstValue("Location").orElse(null)?.let {
                runCatching { URI.create(it) }.getOrNull()
            }?.takeIf(::releaseDownloadRedirect) ?: throw StartupFailure("nativeUpdate.downloadFailed")
            // GitHub can respond with a CDN URL. Its signed URL is sufficient; the
            // repository token belongs only to the first request to api.github.com.
            response = send(redirect, binary, token = null)
        }
        if (response.statusCode() == 200) return response.body()
        response.body().close()
        throw StartupFailure("nativeUpdate.downloadFailed", "HTTP ${response.statusCode()}")
    }

    private fun send(uri: URI, accept: String, token: String?): HttpResponse<InputStream> {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", accept)
            .header("User-Agent", "Voxarium-Studio")
        if (token != null) request.header("Authorization", "Bearer $token")
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    // Public release checks and downloads never read personal GitHub credentials.
    private fun credentials(): String? {
        if (!privateFeed) return null
        val token = sequenceOf("GH_TOKEN", "GITHUB_TOKEN").mapNotNull(System::getenv)
            .firstOrNull(String::isNotBlank) ?: readCliToken()
        return token?.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,512}")) }
            ?: throw StartupFailure("nativeUpdate.feedCredentials")
    }

    private fun readCliToken(): String? {
        val process = runCatching {
            ProcessBuilder("gh", "auth", "token", "--hostname", "github.com")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return null
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            return process.inputStream.use { it.readNBytes(1024).decodeToString().trim().ifBlank { null } }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

private fun InputStream.readBounded(limit: Int): String = readNBytes(limit + 1)
    .also { if (it.size > limit) invalidInstallerManifest() }.decodeToString()
