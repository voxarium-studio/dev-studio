@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Compose Desktop's font cache has no public hook.

package studio.voxarium.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.FontCache
import androidx.compose.ui.text.platform.aliases
import androidx.compose.ui.text.platform.asComposeFontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import java.io.File
import org.jetbrains.jewel.foundation.BorderColors
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.GlobalMetrics
import org.jetbrains.jewel.foundation.OutlineColors
import org.jetbrains.jewel.foundation.Stroke
import org.jetbrains.jewel.foundation.TextColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.defaults
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.ButtonColors
import org.jetbrains.jewel.ui.component.styling.ButtonMetrics
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalDefaultButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalOutlinedButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalTextAreaStyle
import org.jetbrains.jewel.ui.component.styling.LocalTextFieldStyle
import org.jetbrains.jewel.ui.component.styling.TextAreaColors
import org.jetbrains.jewel.ui.component.styling.TextAreaMetrics
import org.jetbrains.jewel.ui.component.styling.TextAreaStyle
import org.jetbrains.jewel.ui.component.styling.TextFieldColors
import org.jetbrains.jewel.ui.component.styling.TextFieldMetrics
import org.jetbrains.jewel.ui.component.styling.TextFieldStyle
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.TypefaceFontProvider

// §7: Graphite surface roles and status colours follow frontend/src/styles/tokens.css.
internal class StudioColors(val dark: Boolean, theme: JsonObject? = null) {
    private val tokens = theme?.obj("custom_tokens")
    private val accessible = theme?.string("status_palette") == "accessible"

    private fun overrideColor(name: String): Color? = tokens?.string("--vxs-$name")?.let(::hexColor)

    private fun color(name: String, fallback: Color): Color = overrideColor(name) ?: fallback

    private fun color(name: String, darkValue: Long, lightValue: Long): Color =
        color(name, Color(if (dark) darkValue else lightValue))

    val canvas = color("bg-canvas", 0xff131313, 0xfff6f6f6)
    val surface = color("bg-surface", 0xff202020, 0xffffffff)
    val elevated = color("bg-elevated", 0xff2a2a2a, 0xffffffff)
    val inset = color("bg-inset", 0xff1c1c1c, 0xffededed)
    val hover = color("bg-hover", 0xff353535, 0xffe8e8e8)
    val active = color("bg-active", 0xff474747, 0xffdcdcdc)
    val strip = color("bg-shell", 0xff323232, 0xfffafafa)
    val sidebar = surface
    val border = color("border", 0xff474747, 0xffdcdcdc)
    val subtleBorder = color("border-subtle", 0xff353535, 0xffebebeb)
    val strongBorder = color("border-strong", 0xff919191, 0xff9a9a9a)
    val selected = color("accent-container", 0xff474747, 0xffebebeb)
    val accentSoft = color("accent-soft", 0xff353535, 0xffe2e2e2)
    val accentBorder = color("accent-border", if (dark) border else Color(0xffc4c4c4))
    val text = color("text-primary", 0xffe2e2e2, 0xff171717)
    val secondary = color("text-secondary", 0xffc6c6c6, 0xff333333)
    val muted = color("text-muted", 0xffc6c6c6, 0xff525252)
    val faint = color("text-faint", 0xff919191, 0xff6a6a6a)
    val accent = overrideColor("accent") ?: overrideColor("accent-seed")
        ?: theme?.string("accent")?.let(::hexColor) ?: Color(0xffa394e8)
    val accentHover = color("accent-hover", lerp(accent, Color.White, .18f))
    val accentPressed = color("accent-pressed", lerp(accent, Color.Black, .18f))
    val onAccent = StudioTokens.ensureContrast(color("text-on-accent", Color.White), accent, StudioTokens.textContrast)
    val onAccentContainer = StudioTokens.ensureContrast(
        color("text-on-accent-container", 0xffe2e2e2, 0xff171717), selected, StudioTokens.textContrast,
    )
    val selection = accent.copy(alpha = .35f)

    val controlBorder = StudioTokens.ensureContrast(strongBorder, inset)
    val scrollbarTrack = inset
    val scrollbarThumb = StudioTokens.ensureContrast(strongBorder, scrollbarTrack)
    val scrollbarThumbHover = StudioTokens.ensureContrast(accent, scrollbarTrack)
    // The common ring can sit directly on canvas or a pane. A ring adjusted only for the
    // white Light pane can drop below 3:1 against the slightly darker canvas next to it.
    val focus = focusOn(canvas).let { onCanvas ->
        if (StudioTokens.contrastRatio(onCanvas, surface) >= StudioTokens.indicatorContrast) onCanvas
        else focusOn(surface).takeIf {
            StudioTokens.contrastRatio(it, canvas) >= StudioTokens.indicatorContrast
        } ?: onCanvas
    }

    fun focusOn(background: Color): Color = StudioTokens.ensureContrast(accent, background)

    private val primaryOverride = overrideColor("accent-gradient")
    val primaryFill: Brush = primaryOverride?.let(::SolidColor)
        ?: if (dark) SolidColor(elevated) else Brush.verticalGradient(listOf(Color(0xfff2f2f2), Color(0xffe6e6e6)))
    val primaryHover = color("accent-gradient-hover", if (dark) hover else Color(0xffe4e4e4))
    val primaryPressed = color("accent-gradient-pressed", if (dark) active else Color(0xffdcdcdc))
    val onPrimary = StudioTokens.ensureContrast(text,
        primaryOverride ?: if (dark) elevated else Color(0xffe6e6e6), StudioTokens.textContrast)

    val railFill = color("rail-fill", Color.Transparent)
    val pageFill = color("page-fill", Color.Transparent)
    val headerFill = color("header-fill", surface)
    val cardFill = color("card-fill", surface)
    val sheetFill = color("sheet-fill", elevated)
    val paneBorder = color("pane-border", border)
    val cardBorder = color("card-border", border)
    val columnBorder = color("column-border", subtleBorder)
    val scrim = canvas.copy(alpha = .7f)
    // §5.3.4: The desktop picker matches the shell's 25% black backdrop in both modes.
    val projectPickerScrim = Color.Black.copy(alpha = .25f)
    val shadowSm = shadow(1, 2, .5f, 1, 2, .06f)
    val shadowMd = shadow(6, 18, .45f, 4, 12, .08f)
    val shadowLg = shadow(24, 60, .6f, 12, 32, .12f)
    val shadowLift = shadow(12, 26, .55f, 8, 20, .11f)

    private fun shadow(darkY: Int, darkBlur: Int, darkAlpha: Float, lightY: Int, lightBlur: Int, lightAlpha: Float) =
        StudioShadow(
            if (dark) darkY.dp else lightY.dp,
            if (dark) darkBlur.dp else lightBlur.dp,
            Color.Black.copy(alpha = if (dark) darkAlpha else lightAlpha),
        )

    val good = if (accessible) color("success", 0xff009e73, 0xff007a59) else color("success", 0xff3fb950, 0xff1a7f37)
    val warning = if (accessible) color("warning", 0xffe69f00, 0xffb07800) else color("warning", 0xffd29922, 0xff9a6700)
    val danger = if (accessible) color("danger", 0xffd55e00, 0xffb34a00) else color("danger", 0xfff85149, 0xffcf222e)
    val info = if (accessible) color("info", 0xff0072b2, 0xff005b8f) else color("info", 0xff58a6ff, 0xff0969da)
    val goodSoft = color("success-soft", good.copy(alpha = StudioTokens.statusTintOpacity))
    val warningSoft = color("warning-soft", warning.copy(alpha = StudioTokens.statusTintOpacity))
    val dangerSoft = color("danger-soft", danger.copy(alpha = StudioTokens.statusTintOpacity))
    val infoSoft = color("info-soft", info.copy(alpha = StudioTokens.statusTintOpacity))
    val warningSurface get() = warningSoft.compositeOver(surface)
    val dangerSurface get() = dangerSoft.compositeOver(surface)
    val infoSurface get() = infoSoft.compositeOver(surface)
    val pink = if (accessible) color("status-blocked", 0xffcc79a7, 0xffcc79a7)
        else color("status-blocked", 0xffdb61a2, 0xffdb61a2)
    val cached = if (accessible) color("status-cached", 0xff56b4e9, 0xff2b8fc6)
        else color("status-cached", 0xff39c5cf, 0xff39c5cf)
    val grid = color("canvas-dot", 0xff262626, 0xffd6d6d6)
    val todo = color("status-todo", 0xff474b55, 0xffa8abb3)
    val pending = color("status-pending", 0xff6b6e76, 0xff6b6e76)
    val textSize = textSize(tokens?.string("--vxs-text-size")?.trim()?.removeSuffix("px"))
        ?: textSize(theme?.string("text_size")) ?: StudioTokens.textSize.value

    private fun textSize(value: String?) = value?.toFloatOrNull()?.takeIf { it.isFinite() && it in 11f..18f }

    private val statuses = StudioStatusRole.entries.associateWith { role ->
        val fallback = when (role) {
            StudioStatusRole.Todo -> todo
            StudioStatusRole.Pending, StudioStatusRole.Cancelled -> Color(0xff6b6e76)
            StudioStatusRole.Running -> warning
            StudioStatusRole.Success -> good
            StudioStatusRole.Error -> danger
            StudioStatusRole.Skipped -> Color(0xff474b55)
            StudioStatusRole.Cached -> cached
            StudioStatusRole.Paused -> if (accessible) Color(0xffcc79a7) else Color(0xffdb61a2)
            StudioStatusRole.Review -> info
            StudioStatusRole.Blocked -> pink
        }
        color("status-${role.token}", fallback)
    }

    /** Original palette value; use the background-aware variant for readable UI indicators. */
    fun statusColor(status: String): Color = statuses.getValue(StudioStatusRole.resolve(status))

    fun statusColor(status: String, background: Color): Color =
        StudioTokens.ensureContrast(statusColor(status), background)

    fun statusTextColor(status: String, background: Color): Color =
        StudioTokens.ensureContrast(statusColor(status), background, StudioTokens.textContrast)

    fun navigationTint(name: String): Color = when (name) {
        "explorer" -> color("nav-explorer", 0xffd9a85c, 0xffa9701f)
        "documents" -> color("nav-documents", 0xff7fb2cf, 0xff3d7f9f)
        "pipelines" -> color("nav-pipelines", 0xffa394e8, 0xff6a56c4)
        "tasks" -> color("nav-tasks", 0xff7fbfa2, 0xff3d8a68)
        "teams" -> color("nav-teams", 0xffe0847e, 0xffb3504a)
        "automations" -> color("nav-automations", 0xffe8935a, 0xffb35a1c)
        "statistics" -> color("nav-statistics", 0xffc095b8, 0xff92628a)
        "vcs" -> color("nav-vcs", 0xff8fb56b, 0xff5c8438)
        "memory" -> color("nav-memory", 0xffbb8ee6, 0xff7a4bb0)
        "skills" -> color("nav-skills", 0xffde8aac, 0xffa64a73)
        "mcp" -> color("nav-mcp", 0xff6fa8b8, 0xff3a7788)
        "plugins" -> color("nav-plugins", 0xffc2a86a, 0xff8f7524)
        "plugin-page" -> color("nav-plugin-page", 0xffa394e8, 0xff6a56c4)
        "update" -> color("nav-update", 0xff7fbfa2, 0xff3d8a68)
        else -> muted
    }

    fun shellBrush(size: androidx.compose.ui.geometry.Size): Brush {
        if (!dark || tokens?.string("--vxs-shell-gradient") == "none") return SolidColor(strip)
        // CSS measures the 115 degree gradient along the window's projected width and height.
        val direction = Offset(.9063f, .4226f)
        val halfLength = (size.width * direction.x + size.height * direction.y) / 2
        val center = Offset(size.width / 2, size.height / 2)
        return Brush.linearGradient(
            0f to Color(0xff2b374f), .52f to Color(0xff3e3145), 1f to Color(0xff233d3e),
            start = center - direction * halfLength, end = center + direction * halfLength,
        )
    }
}

private fun hexColor(value: String): Color? {
    val rgb = value.trim().removePrefix("#")
    if (rgb.length != 6 || rgb.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return rgb.toLongOrNull(16)?.let { Color(0xff000000 or it) }
}

internal fun palette(dark: Boolean) = StudioColors(dark)

/** §2.7: The pre-theme launcher deliberately matches desktop/splash.html. */
internal object StudioLaunchPalette {
    val canvas = Color(0xff17191e)
    val track = Color(0xff30333b)
    val title = Color(0xffeceef2)
    val caption = Color(0xff9a9faa)
    val progress = Color(0xffb0cba4)
    val titleStyle = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-.5).sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    val captionStyle = TextStyle(fontSize = 13.sp, lineHeight = 16.sp)
}

internal val localColors = staticCompositionLocalOf { palette(true) }
internal val localStudioTheme = staticCompositionLocalOf<JsonObject?> { null }
internal val localStudioMono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

// §7: Skia resolves an ordered family list per glyph. FontFamily(Font...) instead chooses
// one candidate by weight/style for the whole run, and lets the OS fill in missing glyphs.
internal object StudioTypography {
    fun style(colors: StudioColors): TextStyle = JewelTheme.createDefaultTextStyle(fontFamily = FontFamily.SansSerif)
        .copy(color = colors.text, fontSize = colors.textSize.sp,
            lineHeight = (colors.textSize * StudioTokens.lineHeightMultiplier).sp)

    private val faces = listOf(400 to "Regular", 500 to "Medium", 600 to "SemiBold", 700 to "Bold")

    private fun TypefaceFontProvider.add(resource: String, alias: String) {
        val bytes = checkNotNull(StudioTypography::class.java.getResourceAsStream(resource)) {
            "Missing bundled UI font: $resource"
        }.use { it.readBytes() }
        Data.makeFromBytes(bytes).use { data ->
            checkNotNull(FontMgr.default.makeFromData(data)) { "Invalid bundled UI font: $resource" }.use {
                registerTypeface(it, alias)
            }
        }
    }

    private fun createResolver(): FontFamily.Resolver {
        // Use Compose Desktop's ordered generic sans aliases in a private FontCache. The
        // aliases only exist in this resolver; fonts are never installed or registered in the OS.
        // Compose 1.12.1 has no public API for supplying ordered custom glyph fallback families.
        val (primary, secondary) = FontFamily.SansSerif.aliases
        val cache = FontCache()
        TypefaceFontProvider().use { fonts ->
            for ((weight, suffix) in faces) {
                fonts.add("/fonts/Allo-Rond100-$weight.ttf", primary)
                fonts.add("/fonts/inter/Inter-$suffix.ttf", secondary)
                val italic = if (weight == 400) "Italic" else "${suffix}Italic"
                fonts.add("/fonts/inter/Inter-$italic.ttf", secondary)
            }
            cache.fonts.setDynamicFontManager(fonts)
        }
        return createFontFamilyResolver(cache)
    }

    val resolver: FontFamily.Resolver by lazy(::createResolver)
}

@OptIn(ExperimentalTextApi::class)
private fun studioMonoFont(choice: String?): FontFamily {
    if (choice == "jetbrains" || choice == null) {
        return FontFamily(Font("fonts/jetbrains-mono/JetBrainsMono-Regular.ttf"))
    }
    if (choice == "fira") {
        val runtimeFont = File(System.getProperty("java.home"), "lib/fonts/FiraCode-Regular.ttf")
        if (runtimeFont.isFile) return FontFamily(Font(runtimeFont))
    }
    val family = when (choice) {
        "cascadia" -> "Cascadia Code"
        "fira" -> "Fira Code"
        else -> return FontFamily.Monospace
    }
    val installed = java.awt.Font(family, java.awt.Font.PLAIN, 12)
    return if (installed.family.equals(family, ignoreCase = true)) installed.asComposeFontFamily() else FontFamily.Monospace
}

@Composable
internal fun StudioTheme(dark: Boolean, theme: JsonObject? = null, content: @Composable () -> Unit) {
    val colors = remember(dark, theme) { StudioColors(dark, theme) }
    val definition = remember(colors) {
        val style = StudioTypography.style(colors)
        val globals = colors.jewelColors()
        val metrics = GlobalMetrics.defaults(outlineWidth = StudioTokens.focusWidth)
        if (dark) JewelTheme.darkThemeDefinition(colors = globals, metrics = metrics, defaultTextStyle = style, contentColor = colors.text)
        else JewelTheme.lightThemeDefinition(colors = globals, metrics = metrics, defaultTextStyle = style, contentColor = colors.text)
    }
    val primary = remember(colors) { colors.button(primary = true) }
    val outlined = remember(colors) { colors.button(primary = false) }
    val selectionColors = remember(colors) { TextSelectionColors(colors.focusOn(colors.inset), colors.selection) }
    val monoFont = theme?.string("mono_font")
    val monospace = remember(monoFont) { studioMonoFont(monoFont) }
    IntUiTheme(definition, ComponentStyling) {
        val iconButtonStyle = LocalTextFieldStyle.current.iconButtonStyle
        val textField = remember(colors, iconButtonStyle) { colors.fieldStyle(iconButtonStyle) }
        val textArea = remember(colors) { colors.areaStyle() }
        CompositionLocalProvider(
            LocalFontFamilyResolver provides StudioTypography.resolver,
            LocalTextSelectionColors provides selectionColors,
            localColors provides colors,
            localStudioTheme provides theme,
            localStudioMono provides monospace,
            LocalDefaultButtonStyle provides primary,
            LocalOutlinedButtonStyle provides outlined,
            LocalTextFieldStyle provides textField,
            LocalTextAreaStyle provides textArea,
            content = content,
        )
    }
}

private fun StudioColors.jewelColors() = GlobalColors(
    borders = BorderColors(normal = border, focused = focus, disabled = subtleBorder),
    outlines = OutlineColors(focused = focus, focusedWarning = StudioTokens.ensureContrast(warning, surface),
        focusedError = StudioTokens.ensureContrast(danger, surface), warning = warning, error = danger),
    text = TextColors(normal = text, selected = onAccentContainer, disabled = faint,
        disabledSelected = faint, info = statusTextColor("needs_review", surface),
        error = statusTextColor("error", surface), warning = statusTextColor("running", surface)),
    panelBackground = surface,
    toolwindowBackground = canvas,
)

private fun StudioColors.fieldStyle(iconButton: org.jetbrains.jewel.ui.component.styling.IconButtonStyle) = TextFieldStyle(
    TextFieldColors(
        background = inset, backgroundDisabled = inset, backgroundFocused = inset,
        backgroundPressed = inset, backgroundHovered = inset,
        content = fieldText, contentDisabled = faint, contentFocused = fieldText, contentPressed = fieldText, contentHovered = fieldText,
        border = controlBorder, borderDisabled = subtleBorder, borderFocused = focusOn(inset),
        borderPressed = controlBorder, borderHovered = fieldHoverBorder,
        caret = fieldText, caretDisabled = faint, caretFocused = fieldText, caretPressed = fieldText, caretHovered = fieldText,
        placeholder = StudioTokens.ensureContrast(faint, inset, StudioTokens.textContrast),
    ),
    TextFieldMetrics(StudioTokens.borderWidth, PaddingValues(horizontal = StudioTokens.space2, vertical = StudioTokens.controlPaddingY),
        CornerSize(StudioTokens.radiusMd), DpSize(0.dp, StudioTokens.controlMinHeight)),
    iconButton,
)

private fun StudioColors.areaStyle() = TextAreaStyle(
    TextAreaColors(
        background = inset, backgroundDisabled = inset, backgroundFocused = inset,
        backgroundPressed = inset, backgroundHovered = inset,
        content = fieldText, contentDisabled = faint, contentFocused = fieldText, contentPressed = fieldText, contentHovered = fieldText,
        border = controlBorder, borderDisabled = subtleBorder, borderFocused = focusOn(inset),
        borderPressed = controlBorder, borderHovered = fieldHoverBorder,
        caret = fieldText, caretDisabled = faint, caretFocused = fieldText, caretPressed = fieldText, caretHovered = fieldText,
        placeholder = StudioTokens.ensureContrast(faint, inset, StudioTokens.textContrast),
    ),
    TextAreaMetrics(StudioTokens.borderWidth, PaddingValues(StudioTokens.space2),
        CornerSize(StudioTokens.radiusMd), DpSize(0.dp, StudioTokens.controlMinHeight)),
)

private val StudioColors.fieldText: Color get() = StudioTokens.ensureContrast(text, inset, StudioTokens.textContrast)
private val StudioColors.fieldHoverBorder: Color get() =
    StudioTokens.ensureContrast(lerp(controlBorder, fieldText, .12f), inset)

private fun StudioColors.button(primary: Boolean): ButtonStyle {
    val background = if (primary) primaryFill else SolidColor(Color.Transparent)
    val hovered = if (primary) primaryHover else hover
    val pressed = if (primary) primaryPressed else active
    val outline = StudioTokens.ensureContrast(strongBorder, surface)
    val content = if (primary) onPrimary else text
    return ButtonStyle(
        ButtonColors(
            background = background, backgroundDisabled = background, backgroundFocused = background,
            backgroundPressed = SolidColor(pressed), backgroundHovered = SolidColor(hovered),
            content = content, contentDisabled = faint, contentFocused = content,
            contentPressed = StudioTokens.ensureContrast(text, pressed, StudioTokens.textContrast),
            contentHovered = StudioTokens.ensureContrast(text, hovered, StudioTokens.textContrast),
            border = SolidColor(outline), borderDisabled = SolidColor(subtleBorder), borderFocused = SolidColor(focus),
            borderPressed = SolidColor(outline), borderHovered = SolidColor(lerp(outline, text, .12f)),
        ),
        ButtonMetrics(CornerSize(StudioTokens.radiusMd),
            PaddingValues(horizontal = StudioTokens.controlPaddingX, vertical = StudioTokens.controlPaddingY),
            DpSize(0.dp, StudioTokens.controlMinHeight), StudioTokens.borderWidth, StudioTokens.focusWidth),
        Stroke.Alignment.Center,
    )
}

@Composable
internal fun StudioIcon(name: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    Image(painterResource("studio-icons/$name.svg"), null, modifier,
        colorFilter = ColorFilter.tint(if (tint == Color.Unspecified) localColors.current.muted else tint))
}
