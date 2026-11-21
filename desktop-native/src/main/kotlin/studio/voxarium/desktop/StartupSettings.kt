package studio.voxarium.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private const val studioLauncher = "Voxarium Studio.exe"

internal data class StartupRegistrySnapshot(
    val installFolders: List<String>,
    val command: String?,
    val approval: List<Int>?,
    val conflictingEntry: Boolean = false,
    val revision: String = "",
)

internal interface StartupRegistry {
    suspend fun read(): StartupRegistrySnapshot
    suspend fun set(expected: StartupRegistrySnapshot, command: String?)
}

internal enum class StartupSetting(val problemKey: String? = null) {
    ON, OFF, UNAVAILABLE("startup.unavailable"), CONFLICT("startup.conflict"),
}

internal fun installedStartupLauncher(command: Path?, resources: Path?): Path? {
    if (command == null || resources == null || !command.isAbsolute || !resources.isAbsolute ||
        !command.fileName.toString().equals(studioLauncher, ignoreCase = true) || !Files.isRegularFile(command)) return null
    val launcher = command.toRealPath()
    val runtime = resources.resolve("studio-backend/run.ps1")
    if (!launcher.fileName.toString().equals(studioLauncher, ignoreCase = true) ||
        !resources.toRealPath().startsWith(launcher.parent) || !Files.isRegularFile(runtime) ||
        !runtime.toRealPath().startsWith(launcher.parent)) return null
    return launcher
}

// §2.7: Windows owns this opt-in setting. Validate the running packaged launcher
// and the matching NSIS registration before touching the shared Electron entry.
internal class WindowsStartup(private val registry: StartupRegistry, private val launcher: () -> Path?) {
    private val io = Mutex()

    suspend fun read(): StartupSetting = io.withLock {
        launcher()?.let { inspect(it, registry.read()) } ?: StartupSetting.UNAVAILABLE
    }

    suspend fun set(enabled: Boolean): StartupSetting = io.withLock {
        val exe = launcher() ?: return@withLock StartupSetting.UNAVAILABLE
        val before = registry.read()
        when (val state = inspect(exe, before)) {
            StartupSetting.CONFLICT, StartupSetting.UNAVAILABLE -> return@withLock state
            else -> if ((state == StartupSetting.ON) == enabled && (enabled || before.command == null)) return@withLock state
        }
        // The registry compares this exact read with the live values before the
        // write. A checkout executable or a conflicting program is never started.
        val requested = if (enabled) "\"$exe\"" else null
        if (requested != null && requested.length > 260) throw StartupFailure("startup.pathTooLong")
        withContext(NonCancellable) { registry.set(before, requested) }
        val actual = inspect(exe, registry.read())
        if (actual != (if (enabled) StartupSetting.ON else StartupSetting.OFF)) throw StartupFailure("startup.failed")
        actual
    }

    private fun inspect(exe: Path, snapshot: StartupRegistrySnapshot): StartupSetting {
        val roots = snapshot.installFolders.map { raw ->
            val path = runCatching { Path.of(raw) }.getOrNull() ?: return StartupSetting.CONFLICT
            if (!path.isAbsolute) return StartupSetting.CONFLICT
            runCatching { path.toRealPath() }.getOrElse { path.normalize() }
        }.distinct()
        if (roots.size > 1) return StartupSetting.CONFLICT
        if (roots.singleOrNull() != exe.parent) return StartupSetting.UNAVAILABLE
        val registered = snapshot.command
        if (snapshot.conflictingEntry || registered != null && !registered.equals("\"$exe\"", ignoreCase = true) &&
            !registered.equals(exe.toString(), ignoreCase = true) || snapshot.approval?.size?.let { it != 12 } == true) {
            return StartupSetting.CONFLICT
        }
        if (registered == null) return StartupSetting.OFF
        return if (snapshot.approval?.firstOrNull() in setOf(null, 0, 2)) StartupSetting.ON else StartupSetting.OFF
    }
}

private object PowerShellStartupRegistry : StartupRegistry {
    private val script = $$"""
        $ErrorActionPreference = 'Stop'
        $OutputEncoding = [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
        $runPath = 'Software\Microsoft\Windows\CurrentVersion\Run'
        $approvalPath = 'Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run'
        $entry = 'Voxarium Studio'
        $appId = 'studio.voxarium.desktop'
        # electron-builder's UUIDv5 of desktop/package.json build.appId.
        $installer = 'Software\6b7b9533-cfd6-5b75-b2c0-1f8ffca3b754'
        $user = [Microsoft.Win32.RegistryKey]::OpenBaseKey('CurrentUser', 'Registry64')
        $machine = [Microsoft.Win32.RegistryKey]::OpenBaseKey('LocalMachine', 'Registry64')
        function Read-Entry($hive, $path, $name) {
            $key = $hive.OpenSubKey($path)
            if ($null -eq $key) { return $null }
            try {
                $value = $key.GetValue($name, $null, 'DoNotExpandEnvironmentNames')
                if ($null -eq $value) { return $null }
                switch ($key.GetValueKind($name)) {
                    'String' {
                        if ($value.Length -gt 512) { return @{ invalid = $true } }
                        return @{ text = $value }
                    }
                    'Binary' {
                        if ($value.Length -ne 12) { return @{ invalid = $true } }
                        return @{ bytes = [Convert]::ToBase64String($value) }
                    }
                    default { return @{ invalid = $true } }
                }
            } finally { $key.Dispose() }
        }
        function Read-State {
            $userFolder = Read-Entry $user $installer 'InstallLocation'
            $machineFolder = Read-Entry $machine $installer 'InstallLocation'
            $launch = Read-Entry $user $runPath $entry
            $approval = Read-Entry $user $approvalPath $entry
            $alternate = ($null -ne (Read-Entry $user $runPath $appId)) -or
                ($null -ne (Read-Entry $machine $runPath $entry)) -or ($null -ne (Read-Entry $machine $runPath $appId))
            $conflict = $alternate -or ($null -ne $launch -and $null -eq $launch.text) -or
                ($null -ne $approval -and $null -eq $approval.bytes) -or
                ($null -ne $userFolder -and $null -eq $userFolder.text) -or
                ($null -ne $machineFolder -and $null -eq $machineFolder.text)
            return [ordered]@{
                folders = @($userFolder.text, $machineFolder.text | Where-Object { $null -ne $_ })
                command = $launch.text
                approval = $approval.bytes
                conflict = $conflict
            } | ConvertTo-Json -Compress
        }
        try {
            $current = Read-State
            if ($env:VOXARIUM_LOGIN_SETTING_ACTION -eq 'read') {
                [Console]::WriteLine($current)
                exit 0
            }
            if ($env:VOXARIUM_LOGIN_SETTING_ACTION -notin @('on', 'off')) { exit 6 }
            if (![String]::Equals($current, $env:VOXARIUM_LOGIN_SETTING_EXPECTED, [StringComparison]::Ordinal)) { exit 4 }
            $run = if ($env:VOXARIUM_LOGIN_SETTING_ACTION -eq 'on') { $user.CreateSubKey($runPath) } else { $user.OpenSubKey($runPath, $true) }
            try {
                if ($env:VOXARIUM_LOGIN_SETTING_ACTION -eq 'on') { $run.SetValue($entry, $env:VOXARIUM_LOGIN_SETTING_COMMAND, 'String') }
                elseif ($null -ne $run) { $run.DeleteValue($entry, $false) }
                # Clearing this marker matches Electron's explicit change and
                # never enables a lingering entry when turning startup off.
                $approval = $user.OpenSubKey($approvalPath, $true)
                if ($null -ne $approval) {
                    try { $approval.DeleteValue($entry, $false) } finally { $approval.Dispose() }
                }
            } finally { if ($null -ne $run) { $run.Dispose() } }
        } catch { exit 5 }
        finally { $user.Dispose(); $machine.Dispose() }
    """.trimIndent()

    override suspend fun read(): StartupRegistrySnapshot {
        val revision = call("read")
        val result = studioJson.parseToJsonElement(revision).jsonObject
        val folders = (result["folders"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: throw StartupFailure("startup.failed")
        val approval = result.string("approval")?.let { Base64.getDecoder().decode(it).map { byte -> byte.toInt() and 255 } }
        return StartupRegistrySnapshot(folders, result.string("command"), approval, result.flag("conflict"), revision)
    }

    override suspend fun set(expected: StartupRegistrySnapshot, command: String?) {
        call(if (command == null) "off" else "on", expected.revision, command.orEmpty())
    }

    private suspend fun call(action: String, expected: String = "", command: String = ""): String = coroutineScope {
        val systemRoot = System.getenv("SystemRoot") ?: throw StartupFailure("startup.failed")
        val executable = Path.of(systemRoot).resolve("System32/WindowsPowerShell/v1.0/powershell.exe")
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-EncodedCommand", encoded).apply {
            environment()["VOXARIUM_LOGIN_SETTING_ACTION"] = action
            environment()["VOXARIUM_LOGIN_SETTING_EXPECTED"] = expected
            environment()["VOXARIUM_LOGIN_SETTING_COMMAND"] = command
            redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()
        try {
            val output = async(Dispatchers.IO) { process.inputStream.use { it.readNBytes(8193) } }
            if (!runInterruptible(Dispatchers.IO) { process.waitFor(8, TimeUnit.SECONDS) }) throw StartupFailure("startup.failed")
            if (process.exitValue() == 4) throw StartupFailure("startup.conflict")
            val bytes = output.await()
            if (process.exitValue() != 0 || bytes.size > 8192) throw StartupFailure("startup.failed")
            bytes.toString(Charsets.UTF_8).trim()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

private fun runningPackagedLauncher(): Path? {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null
    return installedStartupLauncher(ProcessHandle.current().info().command().orElse(null)?.let(Path::of),
        System.getProperty("compose.application.resources.dir")?.let(Path::of))
}

internal suspend fun registeredStudioLauncher(): Path? {
    val launcher = runningPackagedLauncher() ?: return null
    val roots = PowerShellStartupRegistry.read().installFolders.map { raw ->
        val path = runCatching { Path.of(raw) }.getOrNull()?.takeIf { it.isAbsolute } ?: return null
        runCatching { path.toRealPath() }.getOrElse { path.normalize() }
    }.distinct()
    return launcher.takeIf { roots.singleOrNull() == it.parent }
}

private val windowsStartup = WindowsStartup(PowerShellStartupRegistry, ::runningPackagedLauncher)

@Composable
internal fun StartupSettings(m: Messages, tailPadding: Dp = 0.dp) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<StartupSetting?>(null) }
    var error by remember { mutableStateOf<Problem?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun load(enabled: Boolean? = null) {
        if (busy) return
        busy = true
        error = null
        try {
            state = withContext(Dispatchers.IO) { if (enabled == null) windowsStartup.read() else windowsStartup.set(enabled) }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            error = cause.problem("startup.failed")
            state = null
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { load() }
    SettingsSection("startup.title", tailPadding) {
        val available = state in setOf(StartupSetting.ON, StartupSetting.OFF)
        WorkCheckbox(m["startup.enable"], state == StartupSetting.ON, available && !busy) { checked ->
            scope.launch { load(checked) }
        }
        state?.problemKey?.let { Muted(m[it]) }
        error?.let { ProblemText(it) }
        if (busy) Muted(m["loading"])
        if (available || state == StartupSetting.CONFLICT || error != null) LibraryButton("refresh-cw", m["refresh"], !busy) {
            scope.launch { load() }
        }
    }
}
