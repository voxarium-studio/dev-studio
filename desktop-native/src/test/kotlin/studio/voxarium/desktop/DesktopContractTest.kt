package studio.voxarium.desktop

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.platform.Font as ComposeFont
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import java.awt.Font
import java.net.URI
import java.nio.file.Path
import java.util.Locale
import java.util.ResourceBundle
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.DynamicMemoryWStream
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGCanvas
import org.w3c.dom.Element

class DesktopContractTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun nsisRelaunchRestoresTheSessionOrOpensAnExplicitProject() {
        val project = temporary.newFolder("Managed project").toPath().toAbsolutePath().normalize()
        assertNull(launchProject(listOf("--updated")))
        assertEquals(project, launchProject(listOf("--updated", "--project", project.toString())))
        assertEquals(project, launchProject(listOf(project.toString())))
        assertFailsWith<IllegalStateException> { launchProject(listOf("--unknown")) }
    }

    @Test
    fun desktopSessionKeepsExplicitIndicesAndOmitsNullSelectors() {
        val empty = Session(1, listOf(SavedWindow(emptyList(), null)), 0)
        val encoded = studioJson.encodeToString(empty)
        assertContains(encoded, "\"active_tab\":null")
        assertFalse(encoded.contains("geometry"))
        assertEquals(empty, studioJson.decodeFromString<Session>(encoded))

        val page = Page("/explorer", Selectors(path = "assets/100% #map.png"))
        assertEquals(page, studioJson.decodeFromString<Page>(studioJson.encodeToString(page)))
        assertFalse(studioJson.encodeToString(page).contains("chat"))
    }

    @Test
    fun browserFallbackKeepsTheProjectAndLiteralFileSelector() {
        val binding = ProjectBinding("C:/game", "Game", "test-42", "server")
        val page = Page("/explorer", Selectors(path = "assets/100% #map.png"))
        val (route, search) = page.browserTarget()
        val uri = StudioApi().project(binding).web(route, search)
        assertEquals(URI.create("$ORIGIN/p/test-42/explorer?path=assets%2F100%25%20%23map.png"), uri)
    }

    @Test
    fun projectPagesRestoreIntoTheirNativeSections() {
        for (section in listOf(Section.DOCUMENT_GRAPH, Section.STATISTICS, Section.AUTOMATIONS, Section.MCP,
            Section.PROJECT_SETTINGS, Section.REMOTE_AI)) {
            val page = studioJson.decodeFromString<Page>(studioJson.encodeToString(Page(section.route)))
            assertEquals(section, Section.from(page))
            assertFalse(section.usesWorkspaceList)
            assertEquals(section.route to "", page.browserTarget())
        }
    }

    @Test
    fun aSecondLaunchHandsOffItsPathToTheOwner() {
        val directory = temporary.newFolder().toPath()
        val root = Path.of("C:/Game Project")
        requireNotNull(SingleInstance.enter(directory, null)).use { first ->
            assertNull(SingleInstance.enter(directory, root))
            assertEquals(listOf(root.toString()), first.pending())
            assertTrue(first.pending().isEmpty())
        }
        requireNotNull(SingleInstance.enter(directory, null)).close()
    }

    @Test
    fun everyNativeLabelHasARussianTranslation() {
        val default = ResourceBundle.getBundle("studio", Locale.ROOT)
        val russian = requireNotNull(javaClass.getResourceAsStream("/studio_ru.properties")).use { stream ->
            java.util.PropertyResourceBundle(stream)
        }
        assertEquals(default.keySet(), russian.keySet())
        assertEquals("Settings", Messages("en")["settings"])
        assertEquals("Настройки", Messages("ru")["settings"])
    }

    @Test
    fun jewelControlsHaveTheirIconResources() {
        assertNotNull(javaClass.classLoader.getResource("expui/general/chevronDown.svg"))
    }

    @Test
    @OptIn(InternalComposeUiApi::class)
    fun nativeUiShapingUsesAlloAndBundledInterPerGlyphAtEachWeight() {
        val referenceResolver = createFontFamilyResolver()
        val mixed = "Voxarium Студия Ёж"
        val alloFaces = mutableSetOf<PaintedFont>()
        val interFaces = mutableSetOf<PaintedFont>()
        for ((weight, suffix) in listOf(400 to "Regular", 500 to "Medium", 600 to "SemiBold", 700 to "Bold")) {
            // Use the same renderer for the references rather than depending on how
            // this Skia release spells font-family and font-weight in its export.
            fun reference(text: String, resource: String): PaintedFont {
                val paragraph = Paragraph(text, TextStyle(
                    fontFamily = FontFamily(ComposeFont(resource, FontWeight(weight))), fontWeight = FontWeight(weight),
                    fontSize = 14.sp,
                ), constraints = Constraints(maxWidth = 1024), density = Density(1f), fontFamilyResolver = referenceResolver)
                return paintedRuns { paragraph.paint(it) }.single().font
            }

            val allo = reference("V", "fonts/Allo-Rond100-$weight.ttf")
            val inter = reference("Ё", "fonts/inter/Inter-$suffix.ttf")
            assertContains(allo.family, "Allo Round 100")
            assertContains(inter.family, "Inter")
            assertTrue(alloFaces.add(allo), "Allo should render a distinct physical face at $weight")
            assertTrue(interFaces.add(inter), "Inter should render a distinct physical face at $weight")
            val osSymbol = "☃" // deliberately outside both fonts, unlike ∫ (outside Allo only).
            val system = Paragraph(osSymbol, TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight(weight),
                fontSize = 14.sp), constraints = Constraints(maxWidth = 1024), density = Density(1f),
                fontFamilyResolver = referenceResolver)
            val systemFont = paintedRuns { system.paint(it) }.singleOrNull { it.text == osSymbol }?.font
            val sample = "Voxarium Studio / Студия Ёж / $mixed / A∫Z" + if (systemFont != null) " $osSymbol" else ""
            val runs = FrameRecomposer(Dispatchers.Unconfined).use { frames ->
                CanvasLayersComposeScene(frames, size = IntSize(1024, 100)).use { scene ->
                    scene.setContent {
                        StudioTheme(dark = true) {
                            if (weight == 400) Text(sample) else Text(sample, fontWeight = FontWeight(weight))
                        }
                    }
                    frames.performFrame(0)
                    scene.measureAndLayout()
                    paintedRuns { scene.draw(it) }
                }
            }
            assertEquals(sample.filterNot(Char::isWhitespace), runs.joinToString("") { it.text })
            for (run in runs) {
                for (character in run.text) {
                    val expected = when {
                        character in 'А'..'я' || character == 'Ё' || character == '∫' -> inter
                        character == osSymbol.single() -> systemFont
                        else -> allo
                    }
                    assertEquals(expected, run.font, "Glyph '$character', requested weight $weight")
                }
            }
            println("Requested $weight: Latin = ${allo.family}; Cyrillic and ∫ = ${inter.family}; ☃ = ${systemFont?.family}")
        }
    }

    @Test
    fun nativeTypographyKeepsItalicOutlinesAndExplicitMonospace() {
        val style = StudioTypography.style(palette(true))
        fun paragraph(text: String, override: TextStyle) = Paragraph(text, override,
            constraints = Constraints(maxWidth = 1024), density = Density(1f), fontFamilyResolver = StudioTypography.resolver)
        val upright = paragraph("Voxarium", style)
        val italic = paragraph("Voxarium", style.copy(fontStyle = FontStyle.Italic))
        val uprightSvg = paintedSvg(convertTextToPaths = true) { upright.paint(it) }.decodeToString()
        val italicSvg = paintedSvg(convertTextToPaths = true) { italic.paint(it) }.decodeToString()
        assertContains(uprightSvg, "<path ")
        assertContains(italicSvg, "<path ")
        assertNotEquals(uprightSvg, italicSvg, "The Allo italic request should render skewed glyph outlines")

        val mono = paragraph("Voxarium 123", style.copy(fontFamily = FontFamily(ComposeFont("fonts/jetbrains-mono/JetBrainsMono-Regular.ttf"))))
        assertContains(paintedRuns { mono.paint(it) }.single().font.family, "JetBrains Mono")
    }

    private data class PaintedFont(val family: String, val weight: String)
    private data class PaintedRun(val text: String, val font: PaintedFont)

    private fun paintedSvg(convertTextToPaths: Boolean = false, paint: (Canvas) -> Unit): ByteArray =
        DynamicMemoryWStream().use { out ->
            SVGCanvas.make(Rect.makeWH(1024f, 100f), out, convertTextToPaths, false).use { canvas -> paint(canvas.asComposeCanvas()) }
            ByteArray(out.bytesWritten()).also { assertTrue(out.read(it, 0, it.size)) }
        }

    // SVG records the actual glyph runs painted by Compose/Skia, including each
    // selected physical font (not the logical family in the input TextStyle).
    private fun paintedRuns(paint: (Canvas) -> Unit): List<PaintedRun> {
        val svg = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(paintedSvg(paint = paint).inputStream())
        val nodes = svg.getElementsByTagName("text")
        return (0 until nodes.length).mapNotNull {
            val run = nodes.item(it) as Element
            val text = run.textContent.filterNot(Char::isWhitespace)
            if (text.isEmpty()) null else PaintedRun(text, PaintedFont(run.getAttribute("font-family"), run.getAttribute("font-weight")))
        }
    }

    @Test
    fun swingUsesBundledGlyphsBeforeTheLogicalDialogFallback() {
        for ((weight, style) in listOf(400 to Font.PLAIN, 700 to Font.BOLD)) {
            val physical = studioSwingFont(weight)
            val font = studioSwingUiFont(style, 12.5f)
            assertContains(physical.family, "Allo Inter Round 100")
            assertEquals(physical.fontName, font.fontName)
            assertEquals(style, font.style)
            assertEquals(12.5f, font.size2D)
            assertEquals(-1, font.canDisplayUpTo("Voxarium Студия Ёж ∫"))
            val smile = 0x1f603
            assertFalse(physical.canDisplay(smile))
            // This check is conditional because the OS decides which final fallback
            // fonts exist. Windows' standard Dialog stack includes this glyph.
            if (Font(Font.DIALOG, style, 12).canDisplay(smile)) assertTrue(font.canDisplay(smile))
        }
    }

    @Test
    fun aNativeMarkdownDraftKeepsItsCapturedVersionAndBinding() {
        val draft = DocumentDraft("Title", "# Before", "version", "backend-A", 9)
        assertFalse(draft.dirty)
        draft.changeSource(TextFieldValue("# After"))
        assertTrue(draft.dirty)
        val save = draft.payload()
        assertEquals("# After", save.string("content"))
        assertEquals("version", save.string("expected_updated_at"))
        assertEquals("backend-A", save.string("backend_epoch"))
        assertEquals("9", save.string("expected_binding_generation"))
        assertTrue(save.flag("local_edit"))
    }
}
