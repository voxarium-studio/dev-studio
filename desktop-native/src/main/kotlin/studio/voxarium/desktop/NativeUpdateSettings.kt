package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text

private val stableRelease = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)")

internal fun stableReleaseVersion(value: String): List<Int>? =
    stableRelease.matchEntire(value)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }

internal fun newerStableRelease(current: String, candidate: String): Boolean {
    val installed = stableReleaseVersion(current) ?: return false
    val offered = stableReleaseVersion(candidate) ?: return false
    return offered.zip(installed).firstOrNull { (left, right) -> left != right }?.let { (left, right) -> left > right } == true
}

internal class NativeUpdateState(private val scope: CoroutineScope, private val current: String) {
    private val feed = NativeUpdateFeed()
    private var transfer: Job? = null
    private var checking by mutableStateOf(false)
    val busy get() = checking || downloading
    var downloading by mutableStateOf(false)
        private set
    val progress = MutableStateFlow(0)
    var installed by mutableStateOf(false)
        private set
    var checkedAt by mutableStateOf<Instant?>(null)
        private set
    var offer by mutableStateOf<NativeRelease?>(null)
        private set
    var downloaded by mutableStateOf<DownloadedInstaller?>(null)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set

    init {
        scope.launch {
            try {
                installed = registeredStudioLauncher() != null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }

    fun check() {
        if (busy || downloaded != null) return
        checking = true
        problem = null
        scope.launch {
            try {
                offer = withTimeout(45_000) { feed.latest(current) }
                checkedAt = Instant.now()
            } catch (_: TimeoutCancellationException) {
                problem = Problem("nativeUpdate.failed")
            } catch (error: Exception) {
                problem = error.problem("nativeUpdate.failed")
            } finally {
                checking = false
            }
        }
    }

    fun download() {
        val release = offer ?: return
        if (busy || !installed || downloaded != null) return
        downloading = true
        progress.value = 0
        problem = null
        transfer = scope.launch {
            try {
                val root = studioHome().resolve("kotlin-desktop/updates")
                downloaded = withTimeoutOrNull(20 * 60_000L) { feed.download(release, root) { progress.value = it } }
                    ?: throw StartupFailure("nativeUpdate.downloadFailed")
                progress.value = 100
            } catch (error: Exception) {
                problem = error.problem("nativeUpdate.downloadFailed")
            } finally {
                downloading = false
                transfer = null
            }
        }
    }

    fun cancelDownload() { transfer?.cancel() }

    fun installFailed(error: Exception) {
        problem = error.problem("nativeUpdate.installFailed")
        if (error is java.nio.file.NoSuchFileException || error is StartupFailure && error.label == "nativeUpdate.invalidInstaller") {
            downloaded = null
        }
    }
}

@Composable
internal fun NativeUpdateSettings(workspace: Workspace, tailPadding: Dp) {
    val m = localMessages.current
    val state = workspace.releaseUpdates
    val progress by state.progress.collectAsState()
    val prefs = workspace.preferences.obj("updates")
    val enabled = prefs?.flag("enabled") ?: true
    val interval = prefs?.string("check_interval_minutes")?.toIntOrNull() ?: 180
    fun save(key: String, value: JsonPrimitive) {
        workspace.applyPreference(buildJsonObject { putJsonObject("updates") { put(key, value) } })
    }
    SettingsSection("updates", tailPadding) {
        SettingsRow(m["nativeUpdate.current"]) {
            Text(workspace.build.display)
            state.checkedAt?.let { Muted(m["nativeUpdate.checked"].replace("{time}", libraryTime(it.toString(), m.language))) }
        }
        state.problem?.let { ProblemText(it) }
        val release = state.offer
        val installer = state.downloaded
        if (release != null) {
            Text(m["nativeUpdate.available"].replace("{version}", release.tag))
        } else if (state.checkedAt != null && state.problem == null) {
            Muted(m["nativeUpdate.upToDate"])
        }
        if (state.downloading) {
            Muted(m["nativeUpdate.progress"].replace("{percent}", progress.toString()))
        } else if (installer != null) {
            Muted(m["nativeUpdate.downloaded"])
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            when {
                state.downloading -> LibraryButton("x", m["cancel"], onClick = state::cancelDownload)
                installer != null -> LibraryButton("circle-arrow-up", m["nativeUpdate.install"], !workspace.quitting) {
                    workspace.requestUpdateInstall(installer)
                }
                release != null && state.installed -> LibraryButton("download", m["nativeUpdate.download"], !state.busy, state::download)
            }
            LibraryButton("refresh-cw", m["nativeUpdate.check"], !state.busy && installer == null, state::check)
            release?.let { LibraryButton("external-link", m["nativeUpdate.release"]) { openBrowser(it.page, workspace) } }
        }
        WorkCheckbox(m["nativeUpdate.automatic"], enabled, !workspace.preferenceBusy) {
            save("enabled", JsonPrimitive(it))
        }
        SettingsRow(m["nativeUpdate.frequency"]) {
            SettingChoices(interval.toString(), listOf(15, 60, 180, 720, 1440).map { minutes ->
                minutes.toString() to m["nativeUpdate.interval.$minutes"]
            }, enabled && !workspace.preferenceBusy) { save("check_interval_minutes", JsonPrimitive(it.toInt())) }
        }
    }
}
