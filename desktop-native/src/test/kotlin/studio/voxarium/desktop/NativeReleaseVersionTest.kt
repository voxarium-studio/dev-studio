package studio.voxarium.desktop

import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeReleaseVersionTest {
    @Test
    fun comparesStableInstallerVersionsNumerically() {
        assertTrue(newerStableRelease("6.1.9", "v6.1.10"))
        assertTrue(newerStableRelease("6.9.0", "v6.10.0"))
        assertFalse(newerStableRelease("6.1.1", "v6.1.1"))
        assertFalse(newerStableRelease("6.1.1", "v6.1.0"))
        assertFalse(newerStableRelease("6.1.1", "v6.2.0-beta.1"))
        assertFalse(newerStableRelease("6.1.1", "v6.2.0/../latest"))
    }

    @Test
    fun readsOnlyTheExactStableInstallerDeclaredByNsis() {
        val hash = Base64.getEncoder().encodeToString(ByteArray(64))
        val source = """
            version: 6.2.0
            files:
              - url: Voxarium-Studio-Setup-v6.2.0.exe
                sha512: $hash
            path: Voxarium-Studio-Setup-v6.2.0.exe
            sha512: $hash
        """.trimIndent()
        assertEquals(InstallerManifest("6.2.0", "Voxarium-Studio-Setup-v6.2.0.exe", hash),
            parseInstallerManifest(source, "v6.2.0"))
        assertFailsWith<StartupFailure> { parseInstallerManifest(source, "v6.3.0") }
        assertFailsWith<StartupFailure> { parseInstallerManifest("$source\npath: unrelated.exe", "v6.2.0") }
        assertFailsWith<StartupFailure> {
            parseInstallerManifest(source.replace("path: Voxarium", "path: ../Voxarium"), "v6.2.0")
        }
    }

    @Test
    fun releaseAssetRedirectsStayOnGithubHttpsStorage() {
        assertTrue(releaseDownloadRedirect(URI.create("https://release-assets.githubusercontent.com/blob?signature=example")))
        assertFalse(releaseDownloadRedirect(URI.create("https://githubusercontent.com.evil.test/blob")))
        assertFalse(releaseDownloadRedirect(URI.create("http://objects.githubusercontent.com/blob")))
        assertFalse(releaseDownloadRedirect(URI.create("https://user@objects.githubusercontent.com/blob")))
        assertFalse(releaseDownloadRedirect(URI.create("https://objects.githubusercontent.com:8000/blob")))
    }

    @Test
    fun downloadRejectsTruncationAndChecksumMismatch() {
        val bytes = "test update content".encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-512").digest(bytes)
        val checksum = Base64.getEncoder().encodeToString(digest)
        val file = Files.createTempFile("studio-update-test", ".part")
        try {
            copyVerifiedInstaller(bytes.inputStream(), file, bytes.size.toLong(), checksum) {}
            assertContentEquals(bytes, Files.readAllBytes(file))
            assertFailsWith<StartupFailure> {
                copyVerifiedInstaller(bytes.inputStream(), file, bytes.size + 1L, checksum) {}
            }
            assertFailsWith<StartupFailure> {
                copyVerifiedInstaller(bytes.inputStream(), file, bytes.size - 1L, checksum) {}
            }
            assertFailsWith<StartupFailure> {
                copyVerifiedInstaller(bytes.inputStream(), file, bytes.size.toLong(), "different") {}
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
