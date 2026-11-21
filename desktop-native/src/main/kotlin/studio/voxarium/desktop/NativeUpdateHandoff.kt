package studio.voxarium.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal class NativeUpdateHandoff(private val process: Process, private val approval: Path, private val ready: Path) {
    suspend fun approve() = runInterruptible(Dispatchers.IO) {
        if (!process.isAlive || !Files.exists(ready)) throw StartupFailure("nativeUpdate.installFailed")
        Files.createFile(approval)
    }

    fun cancel() {
        if (process.isAlive) process.destroyForcibly()
        runCatching { Files.deleteIfExists(approval) }
        runCatching { Files.deleteIfExists(ready) }
    }
}

// §12.1: arm a helper before stopping the backend, but only authorize it once
// the shared shutdown has saved the session and stopped any owned process.
internal suspend fun prepareNativeUpdate(installer: DownloadedInstaller): NativeUpdateHandoff {
    if (registeredStudioLauncher() == null) throw StartupFailure("nativeUpdate.notInstalled")
    return runInterruptible(Dispatchers.IO) {
        if (Files.size(installer.file) != installer.bytes || installerChecksum(installer.file) != installer.sha512) {
            throw StartupFailure("nativeUpdate.invalidInstaller")
        }
        val resources = System.getProperty("compose.application.resources.dir")?.let(Path::of)
            ?: throw StartupFailure("nativeUpdate.notInstalled")
        val script = resources.resolve("launch-native-update.ps1").takeIf(Files::isRegularFile)
            ?: throw StartupFailure("nativeUpdate.installFailed")
        val systemRoot = System.getenv("SystemRoot") ?: throw StartupFailure("nativeUpdate.installFailed")
        val powershell = Path.of(systemRoot).resolve("System32/WindowsPowerShell/v1.0/powershell.exe")
        val parent = ProcessHandle.current()
        val started = parent.info().startInstant().orElseThrow { StartupFailure("nativeUpdate.installFailed") }
        val nonce = UUID.randomUUID().toString()
        val directory = installer.file.parent
        val ready = directory.resolve("$nonce.ready")
        val approval = directory.resolve("$nonce.approved")
        val process = ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
            "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
            "-StudioProcess", parent.pid().toString(), "-StartedAt", started.toEpochMilli().toString(),
            "-Installer", installer.file.toString(), "-InstallerBytes", installer.bytes.toString(),
            "-Sha512", installer.sha512, "-ReadyFile", ready.toString(), "-ApprovalFile", approval.toString())
            .apply {
                environment().remove("GH_TOKEN")
                environment().remove("GITHUB_TOKEN")
            }
            .redirectErrorStream(true).redirectOutput(directory.resolve("install.log").toFile()).start()
        val handoff = NativeUpdateHandoff(process, approval, ready)
        try {
            repeat(120) {
                if (!process.isAlive) throw StartupFailure("nativeUpdate.installFailed")
                if (Files.exists(ready)) return@runInterruptible handoff
                Thread.sleep(50)
            }
            throw StartupFailure("nativeUpdate.installFailed")
        } catch (error: Exception) {
            handoff.cancel()
            throw error
        }
    }
}

private fun installerChecksum(file: Path): String {
    val hash = MessageDigest.getInstance("SHA-512")
    Files.newInputStream(file).use { input ->
        val block = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(block)
            if (read < 0) break
            hash.update(block, 0, read)
        }
    }
    return Base64.getEncoder().encodeToString(hash.digest())
}
