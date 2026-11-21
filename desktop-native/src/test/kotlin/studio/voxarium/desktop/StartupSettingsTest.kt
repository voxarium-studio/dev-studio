package studio.voxarium.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class StartupSettingsTest {
    @get:Rule
    val files = TemporaryFolder()

    private class Registry(var current: StartupRegistrySnapshot) : StartupRegistry {
        var writes = 0
        var reads = 0

        override suspend fun read(): StartupRegistrySnapshot {
            reads++
            return current
        }

        override suspend fun set(expected: StartupRegistrySnapshot, command: String?) {
            assertEquals(current, expected)
            writes++
            current = current.copy(command = command, approval = null)
        }
    }

    private fun makeLauncher(folder: String = "Voxarium Studio installed"): Pair<Path, Path> {
        val install = files.newFolder(folder).toPath().toRealPath()
        val executable = Files.createFile(install.resolve("Voxarium Studio.exe"))
        val resources = Files.createDirectories(install.resolve("app/resources"))
        Files.createFile(Files.createDirectories(resources.resolve("studio-backend")).resolve("run.ps1"))
        return executable to resources
    }

    @Test
    fun anInstalledLaunchUsesTheSharedQuotedPathOnlyAfterOptIn() = runBlocking {
        val (exe, resources) = makeLauncher()
        val registry = Registry(StartupRegistrySnapshot(listOf(exe.parent.toString()), null, null))
        val startup = WindowsStartup(registry) { installedStartupLauncher(exe, resources) }

        assertEquals(StartupSetting.OFF, startup.read())
        assertEquals(0, registry.writes)
        assertEquals(StartupSetting.ON, startup.set(true))
        assertEquals("\"$exe\"", registry.current.command)
        assertEquals(1, registry.writes)
        assertEquals(StartupSetting.ON, startup.set(true))
        assertEquals(1, registry.writes)
        assertEquals(StartupSetting.OFF, startup.set(false))
        assertNull(registry.current.command)
        assertEquals(2, registry.writes)
    }

    @Test
    fun electronEntryRetainsItsEffectiveWindowsApproval() = runBlocking {
        val (exe, resources) = makeLauncher()
        val approval = listOf(3) + List(11) { 0 }
        val registry = Registry(StartupRegistrySnapshot(listOf(exe.parent.toString()), exe.toString(), approval))
        val startup = WindowsStartup(registry) { installedStartupLauncher(exe, resources) }

        assertEquals(StartupSetting.OFF, startup.read())
        assertEquals(0, registry.writes)
        assertEquals(StartupSetting.ON, startup.set(true))
        assertNull(registry.current.approval)
        assertEquals("\"$exe\"", registry.current.command)
    }

    @Test
    fun checkoutAndJavaProcessesCannotRegisterAutostart() = runBlocking {
        val (unpacked, resources) = makeLauncher("Unpacked build")
        val (installed, _) = makeLauncher("Registered install")
        val java = Files.createFile(unpacked.parent.resolve("java.exe"))
        val registry = Registry(StartupRegistrySnapshot(listOf(installed.parent.toString()), null, null))
        val dev = WindowsStartup(registry) { installedStartupLauncher(java, resources) }

        assertEquals(StartupSetting.UNAVAILABLE, dev.set(true))
        assertEquals(0, registry.reads)
        assertEquals(StartupSetting.UNAVAILABLE, WindowsStartup(registry) { installedStartupLauncher(unpacked, resources) }.set(true))
        assertEquals(0, registry.writes)
    }

    @Test
    fun ambiguousInstallPathsAndUnknownCommandsAreReadOnly() = runBlocking {
        val (exe, resources) = makeLauncher()
        val (other, _) = makeLauncher("Other installation")
        val installed = listOf(exe.parent.toString())
        val registry = Registry(StartupRegistrySnapshot(installed + other.parent.toString(), null, null))
        val startup = WindowsStartup(registry) { installedStartupLauncher(exe, resources) }
        assertEquals(StartupSetting.CONFLICT, startup.set(true))

        registry.current = registry.current.copy(installFolders = installed, command = "\"$exe\" --project Game")
        assertEquals(StartupSetting.CONFLICT, startup.set(false))
        registry.current = registry.current.copy(command = null, conflictingEntry = true)
        assertEquals(StartupSetting.CONFLICT, startup.set(true))
        assertEquals(0, registry.writes)
    }
}
