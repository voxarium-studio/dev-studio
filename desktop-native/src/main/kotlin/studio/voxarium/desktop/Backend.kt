package studio.voxarium.desktop

import java.net.ConnectException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible

internal fun studioHome(): Path {
    val override = System.getenv("VOXARIUM_STUDIO_HOME")?.takeIf(String::isNotBlank)
    val user = Path.of(System.getProperty("user.home"))
    if (override != null) {
        return if (override == "~" || override.startsWith("~/") || override.startsWith("~\\")) {
            user.resolve(override.drop(2)).toAbsolutePath()
        } else Path.of(override).toAbsolutePath()
    }
    val appData = System.getenv("APPDATA")?.let(Path::of) ?: user.resolve("AppData/Roaming")
    return appData.resolve("voxarium_studio")
}

// A second launcher hands off the requested path and exits without touching the backend (§2.7).
internal class SingleInstance private constructor(
    private val directory: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    companion object {
        fun enter(directory: Path, project: Path?): SingleInstance? {
            Files.createDirectories(directory)
            val channel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            var lock: FileLock? = null
            try {
                lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) return SingleInstance(directory, channel, lock)
                val id = UUID.randomUUID()
                val temporary = directory.resolve("$id.tmp")
                temporary.writeText(project?.toString().orEmpty())
                Files.move(temporary, directory.resolve("$id.open"), ATOMIC_MOVE)
                return null
            } finally {
                if (lock == null) channel.close()
            }
        }
    }

    fun pending(): List<String> = Files.newDirectoryStream(directory, "*.open").use { requests ->
        requests.mapNotNull { request ->
            runCatching {
                val value = request.readText()
                Files.delete(request)
                value
            }.getOrNull()
        }
    }

    override fun close() {
        lock.release()
        channel.close()
    }
}

internal class StartupFailure(val label: String, val details: String? = null) : Exception(details ?: label)

internal class Backend(private val api: StudioApi, private val build: BuildIdentity, private val directory: Path) {
    private val instance = UUID.randomUUID().toString()
    private val runDir = directory.resolve("run")
    private val stopFile = runDir.resolve("$instance.stop")
    private val packaged = System.getProperty("compose.application.resources.dir")?.let(Path::of)
    private val source = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { it.resolve("desktop-native/build.gradle.kts").isRegularFile() }
    private var launcher: Process? = null
    private var launched = false

    var owned: Boolean = false
        private set

    suspend fun start(project: Path?): Health {
        val existing = try {
            api.health()
        } catch (_: ConnectException) {
            null
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw StartupFailure("portUnavailable", error.message)
        }
        if (existing != null) return checked(existing)

        val runtime = packaged?.resolve("studio-backend")?.takeIf { it.resolve("run.ps1").isRegularFile() }
            ?: source ?: throw StartupFailure("missingRuntime")

        val args = mutableListOf("-File", runtime.resolve("run.ps1").toString(), "-Desktop", "-NoOpen")
        // The CLI initializes a new directory when it receives -Project. Keep
        // initialization behind the project picker's explicit confirmation (§5.3.2).
        if (project?.resolve(".voxarium_studio")?.isDirectory() == true) {
            args.addAll(listOf("-Project", project.toString()))
        }
        launcher = runInterruptible(Dispatchers.IO) {
            Files.createDirectories(runDir)
            powershell(args)
                .directory(runtime.toFile())
                .apply {
                    environment()["VOXARIUM_STUDIO_RUN_DIR"] = runDir.toString()
                    environment()["VOXARIUM_STUDIO_DESKTOP_INSTANCE"] = instance
                    environment()["VOXARIUM_STUDIO_DESKTOP_PARENT"] = ProcessHandle.current().pid().toString()
                    environment()["VOXARIUM_STUDIO_DESKTOP_STOP_FILE"] = stopFile.toString()
                }
                .redirectErrorStream(true)
                .redirectOutput(runDir.resolve("launcher.log").toFile())
                .start()
        }
        launched = true

        repeat(100) {
            val health = try {
                api.health()
            } catch (_: ConnectException) {
                null
            }
            if (health?.desktopInstance == instance) {
                owned = true
                return checked(health)
            }
            if (launcher?.isAlive == false) throw StartupFailure("startFailed", runDir.toString())
            delay(500)
        }
        throw StartupFailure("startFailed", runDir.toString())
    }

    private fun checked(health: Health): Health {
        if (health.service != "voxarium_studio" || health.status != "ok") throw StartupFailure("portUnavailable")
        if (health.version != build.version) {
            throw StartupFailure("versionMismatch", "${build.version} / ${health.version}")
        }
        return health
    }

    // §2.7: the backend watches our process handle and owns the Windows job. The existing
    // stop helper waits on the verified process handle even after HTTP stops responding.
    suspend fun stop() {
        if (!launched) return
        runInterruptible(Dispatchers.IO) {
            val stopScript = packaged?.resolve("stop-backend.ps1")?.takeIf { it.isRegularFile() }
                ?: source?.resolve("desktop/scripts/stop-backend.ps1")
                ?: throw StartupFailure("missingRuntime")
            val helper = powershell(listOf("-File", stopScript.toString(), "-StopFile", stopFile.toString()))
                .redirectErrorStream(true)
                .redirectOutput(runDir.resolve("stop.log").toFile())
                .start()
            if (!helper.waitFor(27, TimeUnit.SECONDS)) {
                helper.destroyForcibly()
                throw StartupFailure("stopFailed", runDir.toString())
            }
            if (helper.exitValue() != 0) throw StartupFailure("stopFailed", runDir.toString())
            owned = false
        }
    }

    private fun powershell(args: List<String>) = ProcessBuilder(
        listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass") + args,
    )
}
