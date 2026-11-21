package studio.voxarium.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.util.Locale
import java.util.ResourceBundle
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource
import javax.swing.text.StyleContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

internal class Messages(val language: String) {
    private val bundle = ResourceBundle.getBundle("studio", Locale.forLanguageTag(language),
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))
    operator fun get(key: String): String = bundle.getString(key)
    fun status(value: String): String = if (bundle.containsKey("status.$value")) this["status.$value"] else value
}

internal val localMessages = staticCompositionLocalOf { Messages("en") }

internal fun launchProject(arguments: List<String>): Path? {
    // §2.7.1: the NSIS finish page and the Electron updater relaunch with this flag.
    val args = if (arguments.firstOrNull() == "--updated") arguments.drop(1) else arguments
    return when {
        args.isEmpty() -> null
        args.size == 2 && args[0] == "--project" -> Path.of(args[1]).toAbsolutePath().normalize()
        args.size == 1 && !args[0].startsWith('-') -> Path.of(args[0]).toAbsolutePath().normalize()
        else -> error("Usage: Voxarium Studio [--project <directory>]")
    }
}

fun main(args: Array<String>) {
    val project = launchProject(args.asList())
    val directory = studioHome().resolve("kotlin-desktop")
    val instance = SingleInstance.enter(directory, project) ?: return
    instance.use {
        // §7: use the same glyph sources in Swing pickers as in the Compose windows.
        SwingUtilities.invokeAndWait(::setSwingTypography)
        val input = requireNotNull(BuildIdentity::class.java.getResourceAsStream("/studio-build.json"))
        val build = input.bufferedReader().use { studioJson.decodeFromString<BuildIdentity>(it.readText()) }
        val api = StudioApi()
        val backend = Backend(api, build, directory)
        application {
            val applicationScope = rememberCoroutineScope()
            val workspace = remember { Workspace(applicationScope, api, backend, build, ::exitApplication) }
            val language = workspace.preferences.string("language") ?: Locale.getDefault().language
            val messages = remember(language) { Messages(if (language == "ru") "ru" else "en") }
            val dark = when (workspace.preferences.obj("theme")?.string("mode")) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }
            val icon = painterResource("studio-logo.png")
            var reducedMotion by remember { mutableStateOf(systemReducedMotion()) }
            LaunchedEffect(workspace) { workspace.boot(project) }
            LaunchedEffect(workspace.ready) {
                if (workspace.ready) workspace.observeProducerAttention()
            }
            val updatePreferences = workspace.preferences.obj("updates")
            LaunchedEffect(workspace.ready, updatePreferences) {
                if (workspace.ready && updatePreferences?.flag("enabled") != false) {
                    delay(15_000)
                    val interval = (updatePreferences?.string("check_interval_minutes")?.toLongOrNull() ?: 180L)
                        .coerceIn(5, 1440)
                    while (true) {
                        workspace.releaseUpdates.check()
                        delay(interval * 60_000)
                    }
                }
            }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2000)
                    reducedMotion = systemReducedMotion()
                }
            }
            LaunchedEffect(instance) {
                while (true) {
                    if (!workspace.booting) withContext(Dispatchers.IO) { instance.pending() }.forEach(workspace::handoff)
                    delay(500)
                }
            }

            CompositionLocalProvider(localMessages provides messages, LocalStudioReducedMotion provides reducedMotion) {
                if (isTraySupported) {
                    Tray(icon, tooltip = "Voxarium Studio", onAction = workspace::show, menu = {
                        Item(messages["showStudio"], onClick = workspace::show)
                        workspace.producerAttention.sortedBy(NativeProducerAttention::project).forEach { waiting ->
                            Item(messages["producerAttention.tray"].replace("{project}", waiting.project)
                                .replace("{count}", waiting.chats.size.toString()),
                                enabled = !workspace.booting && !workspace.quitting,
                                onClick = { workspace.activateProducerAttention(waiting) })
                        }
                        Separator()
                        Item(messages["quit"], enabled = !workspace.booting && !workspace.quitting, onClick = workspace::requestQuit)
                    })
                }
                if (workspace.booting && workspace.windows.isEmpty()) {
                    Window(onCloseRequest = ::exitApplication, title = "Voxarium Studio", icon = icon,
                        undecorated = true, resizable = false, state = rememberWindowState(width = 460.dp, height = 300.dp,
                            position = WindowPosition.Aligned(Alignment.Center))) {
                        StudioTheme(dark) { StudioSplash(icon, messages["starting"]) }
                    }
                }
                workspace.windows.forEach { model ->
                    key(model.id) { StudioWindow(model, workspace, dark, icon) }
                }
                val problem = workspace.problem
                if (problem != null && !workspace.ready && !workspace.booting) {
                    StudioDialog(messages["startFailed"], dark, onClose = workspace::requestQuit) {
                        ProblemText(problem)
                        WorkAction(messages["quit"], null, enabled = !workspace.quitting, onClick = workspace::requestQuit)
                    }
                }
                workspace.confirmation?.let { confirmation ->
                    StudioDialog(messages[confirmation.action], dark, workspace::dismissConfirmation) {
                        Text(messages[confirmation.key])
                        confirmation.details?.takeIf(String::isNotBlank)?.let { Muted(it) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                            WorkAction(messages["cancel"], null, onClick = workspace::dismissConfirmation)
                            WorkAction(messages[confirmation.action], null, primary = true, onClick = workspace::confirm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioSplash(icon: Painter, message: String) {
    // §2.7: the launcher uses the same initial paint as desktop/splash.html.
    val paint = StudioLaunchPalette
    Column(Modifier.fillMaxSize().background(paint.canvas).border(StudioTokens.borderWidth, paint.track)
        .padding(StudioTokens.panePad + StudioTokens.space4 + StudioTokens.space1),
        verticalArrangement = Arrangement.Center) {
        Image(icon, null, Modifier.size(StudioTokens.largeIconSize * 2))
        Spacer(Modifier.height(StudioTokens.space4))
        Text("Voxarium Studio", color = paint.title, fontSize = paint.titleStyle.fontSize,
            lineHeight = paint.titleStyle.lineHeight, letterSpacing = paint.titleStyle.letterSpacing,
            fontWeight = paint.titleStyle.fontWeight)
        Spacer(Modifier.height(StudioTokens.space2))
        Text(message, color = paint.caption, fontSize = paint.captionStyle.fontSize, lineHeight = paint.captionStyle.lineHeight)
        Spacer(Modifier.height(StudioTokens.space6))
        val progress = if (LocalStudioReducedMotion.current) .325f else {
            val transition = rememberInfiniteTransition(label = "startup")
            val position by transition.animateFloat(-.35f, 1.365f,
                infiniteRepeatable(tween(StudioMotion.splashSweep, easing = LinearEasing)), label = "progress")
            position
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(StudioTokens.focusWidth).background(paint.track).clipToBounds()) {
            Box(Modifier.offset(x = maxWidth * progress).width(maxWidth * .35f).fillMaxHeight().background(paint.progress))
        }
    }
}

internal fun studioSwingFont(weight: Int): Font {
    // AWT cannot express Compose's ordered family chain for a physical font. The
    // generated face includes only the Inter glyphs the corresponding Allo face lacks.
    return requireNotNull(StudioTypography::class.java.getResourceAsStream("/fonts/Allo-Inter-Rond100-$weight.ttf")).use {
        Font.createFont(Font.TRUETYPE_FONT, it)
    }
}

private val swingStudioFontFamily by lazy {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val fonts = listOf(studioSwingFont(400), studioSwingFont(700))
    for (font in fonts) check(environment.registerFont(font)) { "Cannot register the bundled Swing font: ${font.fontName}" }
    fonts.first().family
}

internal fun studioSwingUiFont(style: Int, size: Float): Font =
    // StyleContext wraps JVM-local registered faces in the logical Dialog fallbacks.
    StyleContext.getDefaultStyleContext().getFont(swingStudioFontFamily, style, size.toInt()).deriveFont(size)

private fun setSwingTypography() {
    val defaults = UIManager.getLookAndFeelDefaults()
    defaults.keys.toList().forEach { key ->
        val existing = defaults.getFont(key) ?: return@forEach
        UIManager.put(key, FontUIResource(studioSwingUiFont(existing.style, existing.size2D)))
    }
}

@Composable
private fun StudioDialog(title: String, dark: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DialogWindow(onCloseRequest = onClose, title = title, resizable = false, state = rememberDialogState(
        position = WindowPosition.Aligned(Alignment.Center), width = 480.dp, height = 235.dp,
    )) {
        StudioTheme(dark) {
            Column(Modifier.fillMaxSize().background(localColors.current.canvas).padding(StudioTokens.panePad),
                verticalArrangement = Arrangement.Center) {
                Column(Modifier.studioArrival(duration = StudioMotion.normal),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space3), content = content)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StudioWindow(model: WorkspaceWindow, workspace: Workspace, dark: Boolean, icon: Painter) {
    val geometry = remember { model.geometry }
    val bounds = remember(geometry) {
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        geometry?.takeIf { saved ->
            screen.any { it.defaultConfiguration.bounds.contains(saved.x + 24, saved.y + 24) }
        }
    }
    val state = rememberWindowState(
        size = if (bounds == null) androidx.compose.ui.unit.DpSize(1600.dp, 1000.dp)
            else androidx.compose.ui.unit.DpSize(bounds.width.dp, bounds.height.dp),
        position = if (bounds == null) WindowPosition.Aligned(Alignment.Center)
            else WindowPosition(bounds.x.dp, bounds.y.dp),
        placement = if (bounds?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
    )
    val closeWindow = {
        if (isTraySupported || workspace.windows.size > 1) workspace.closeWindow(model) else workspace.requestQuit()
    }
    val searchTab = model.active
    val searchApi = searchTab?.api
    var searchAvailable by remember(searchApi) { mutableStateOf(false) }
    var searchVisible by remember(searchApi) { mutableStateOf(false) }
    LaunchedEffect(searchApi, searchTab?.chromePluginPages, searchTab?.chromeProject) {
        if (searchApi != null) {
            try {
                searchAvailable = searchApi.get("/search/providers").objects().isNotEmpty()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                searchAvailable = false
            }
        }
    }
    Window(onCloseRequest = closeWindow,
        title = model.active?.binding?.name?.let { "$it — Voxarium Studio" } ?: "Voxarium Studio",
        state = state, visible = model.visible, icon = icon, decoration = WindowDecoration.Undecorated(),
        onPreviewKeyEvent = { event ->
            if (searchAvailable && !model.picking && event.key == Key.K && event.isCtrlPressed && event.type == KeyEventType.KeyDown) {
                searchVisible = true
                true
            } else false
        }) {
        val owner = window
        val attention = workspace.producerAttention.count { waiting -> model.tabs.any { waiting.matches(it.binding) } }
        LaunchedEffect(owner, attention > 0) { setProducerTaskbarAttention(owner, attention > 0) }
        DisposableEffect(owner) {
            owner.minimumSize = Dimension(900, 600)
            val focusListener = object : java.awt.event.WindowAdapter() {
                override fun windowGainedFocus(event: java.awt.event.WindowEvent) { workspace.focus(model, raise = false) }
            }
            owner.addWindowFocusListener(focusListener)
            onDispose { owner.removeWindowFocusListener(focusListener) }
        }
        LaunchedEffect(model.activation) {
            if (model.activation > 0 && model.visible) {
                state.isMinimized = false
                owner.toFront()
                owner.requestFocus()
            }
        }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.position, state.size, state.placement) }.collect { (position, size, placement) ->
                if (!position.isSpecified || size.width.value < 100 || size.height.value < 100) return@collect
                val previous = model.geometry
                model.geometry = if (placement == WindowPlacement.Maximized && previous != null) previous.copy(maximized = true)
                    else Geometry(position.x.value.toInt(), position.y.value.toInt(),
                        size.width.value.toInt(), size.height.value.toInt(), placement == WindowPlacement.Maximized)
                workspace.scheduleSave()
            }
        }
        val applicationTheme = workspace.preferences.obj("theme")
        val projectAccent = model.active?.chromeProject?.obj("config")?.obj("theme")?.get("accent")
        val theme = if (projectAccent == null) applicationTheme
            else kotlinx.serialization.json.JsonObject(applicationTheme.orEmpty() + ("accent" to projectAccent))
        StudioTheme(dark, theme) {
            StudioShell(model, workspace, state, painterResource("studio-titlebar-logo.png"), closeWindow,
                openSearch = if (searchAvailable) ({ searchVisible = true }) else null)
            if (searchVisible && searchTab != null) NativeProjectSearch(searchTab, close = { searchVisible = false }) { page ->
                searchVisible = false
                searchTab.navigate(page)
                model.settings = false
                workspace.scheduleSave()
            }
        }
    }
}
