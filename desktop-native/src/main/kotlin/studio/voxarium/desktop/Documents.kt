package studio.voxarium.desktop

import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import java.net.URI
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalEditorTextStyle
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.standalone.styling.dark
import org.jetbrains.jewel.intui.markdown.standalone.styling.light
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.rendering.ImageSourceResolver
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

internal fun documentEndpoint(path: String): String = "/docs/" + path.split('/').joinToString("/", transform = ::segment)

internal class DocumentNeedsBrowser : IOException()

@OptIn(ExperimentalJewelApi::class)
private object DisabledMarkdownImages : ImageSourceResolver {
    override fun resolve(rawDestination: String): String? = null
}

internal class DocumentDraft(
    val title: String,
    private val original: String,
    private val updatedAt: String,
    private val backendEpoch: String,
    private val generation: Long,
) {
    var text by mutableStateOf(TextFieldValue(original))
        private set
    val dirty: Boolean get() = text.text != original
    private var blocks: DocumentBlocks? = null

    fun blocks(): DocumentBlocks = blocks ?: DocumentBlocks(text.text).also { blocks = it }

    fun changeSource(value: TextFieldValue) {
        if (value.text != text.text) blocks = null
        text = value
    }

    fun changeBlocks() {
        val content = blocks().source()
        if (content != text.text) text = TextFieldValue(content, TextRange.Zero)
    }

    fun payload() = buildJsonObject {
        put("content", text.text)
        put("expected_updated_at", updatedAt)
        put("backend_epoch", backendEpoch)
        put("expected_binding_generation", generation)
        put("local_edit", true)
    }
}

private enum class DocumentEditView { BLOCKS, SOURCE, PREVIEW }

// §2.7.1: capture the binding before reading the document. A subsequent sync
// transition, write, rename or deletion must fail the save and retain the draft.
private suspend fun ProjectApi.beginDocumentEdit(path: String): DocumentDraft {
    val remote = get("/remote").jsonObject
    if (!remote.flag("local_document_editing")) {
        throw DocumentNeedsBrowser()
    }
    val epoch = requireNotNull(remote.string("backend_epoch"))
    val generation = requireNotNull(remote.string("binding_generation")?.toLongOrNull())
    val document = get(documentEndpoint(path)).jsonObject
    return DocumentDraft(
        document.string("title") ?: path,
        requireNotNull(document.string("content")),
        requireNotNull(document.string("updated_at")),
        epoch,
        generation,
    )
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun DocumentPanel(
    tab: ProjectTab,
    path: String,
    detail: WorkspaceDetail?,
    navigate: (Page) -> Unit,
    workspace: Workspace,
    graphDocuments: List<WorkspaceItem>? = null,
    graphProblem: Problem? = null,
    closeReader: (() -> Unit)? = null,
    manageDocument: ((DocumentTreeAction) -> Unit)? = null,
) {
    val m = localMessages.current
    val colors = localColors.current
    val view = tab.libraryWorkspace
    val readingPage = tab.page
    val recordSelection: (String) -> Unit = { text ->
        if (tab.page == readingPage && readingPage.query.path == path) tab.chatSelection.update(readingPage, text)
    }
    val graphReader = closeReader != null
    val draft = if (graphReader) null else tab.documentDrafts[path]
    val problem = if (graphReader) graphProblem else tab.detailError
    var editView by remember(draft) { mutableStateOf(DocumentEditView.BLOCKS) }
    val sourceLabel = m["documentSource"]
    val projectReading = tab.chromeProject?.obj("config")?.obj("docs")
    var appliedReading by remember(projectReading) { mutableStateOf<JsonObject?>(null) }
    val reading = appliedReading ?: projectReading
    val textSize = reading?.string("font_size")?.toFloatOrNull()?.coerceIn(10f, 28f) ?: colors.textSize + 1f
    val leading = reading?.string("line_height")?.toFloatOrNull()?.coerceIn(1f, 2.5f) ?: 1.5f
    // §7: reuse the scoped settings patch; never rewrite the rest of the project's Docs config.
    fun changeReading(field: String, value: JsonPrimitive?) {
        tab.action {
            appliedReading = put("/settings", buildJsonObject {
                put("docs", buildJsonObject { put(field, value ?: JsonNull) })
            }).jsonObject.obj("docs")
            tab.refreshChrome()
        }
    }
    val textStyle = JewelTheme.defaultTextStyle
    val monospace = localStudioMono.current
    val markdownStyle = remember(colors, textStyle, textSize, leading, monospace) {
        documentMarkdownStyle(colors, textStyle, textSize, leading, monospace)
    }
    val originalLinks = detail?.data?.objects("links").orEmpty()
    val documents = graphDocuments ?: tab.list?.items.orEmpty()
    fun followDocument(target: String) {
        if (documents.any { it.id == target }) {
            navigate(Page(if (graphReader) "/documents/graph" else "/documents", Selectors(path = target)))
        }
    }
    val rendering = draft == null || editView == DocumentEditView.PREVIEW
    val markdown = if (rendering) draft?.text?.text ?: detail?.data?.string("content").orEmpty() else ""
    val blocks by produceState(emptyList<MarkdownBlock>(), markdown, originalLinks, documents, rendering) {
        if (rendering) value = withContext(Dispatchers.Default) {
            val paths = originalLinks.associate { it.string("target") to it.string("path") }
            readDocumentMarkdown(markdown) { target ->
                if (target in paths) paths[target] else resolveDocumentLink(target, documents)
            }
        }
    }
    val outline = remember(blocks) { documentOutline(blocks) }
    val scroll = rememberScrollState()
    val headingTops = remember(outline) { mutableStateMapOf<Int, Int>() }
    var viewportTop by remember { mutableIntStateOf(0) }
    val outlineInset = with(LocalDensity.current) { StudioTokens.space4.roundToPx() }
    val reducedMotion = LocalStudioReducedMotion.current
    val api = tab.api
    val imageLoader = remember(api, path) {
        api?.let { project ->
            val load: suspend (String) -> ByteArray = { src ->
                project.downloadAttachment("/docs/asset" + query("path" to path, "src" to src), 32 * 1024 * 1024)
            }
            load
        }
    }
    val renderer = remember(markdownStyle, colors, outline, scroll, imageLoader) {
        DocumentBlockRenderer(markdownStyle, colors, outline, positionHeading = { index, position ->
            headingTops[index] = position.roundToInt() + scroll.value
        }, imageLoader = imageLoader)
    }
    val activeHeading by remember(outline, scroll, outlineInset) {
        derivedStateOf {
            val limit = viewportTop + scroll.value + outlineInset + 1
            outline.indices.lastOrNull { index -> headingTops[index]?.let { it <= limit } == true }
        }
    }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        LibraryHeader(draft?.title ?: detail?.item?.title ?: path, path, titleSize = colors.textSize.sp,
            monoSubtitle = true) {
            if (closeReader != null) {
                DocumentHeaderAction("file-pen", m["docGraph.openDocument"]) {
                    navigate(Page("/documents", Selectors(path = path)))
                }
                DocumentHeaderAction("x", m["close"], onClick = closeReader)
                return@LibraryHeader
            }
            if (draft == null) {
                DocumentHeaderAction("pencil", m["documentBlocks.editDocument"],
                    enabled = !tab.acting && tab.detailError == null) {
                    tab.action { tab.documentDrafts[path] = beginDocumentEdit(path) }
                }
                if (manageDocument != null && detail != null) {
                    DocumentHeaderAction("folder-symlink", m["docTree.move"], enabled = !tab.acting) {
                        manageDocument(DocumentTreeAction.MOVE)
                    }
                    DocumentHeaderAction("trash-2", m["docTree.delete"], enabled = !tab.acting) {
                        manageDocument(DocumentTreeAction.DELETE)
                    }
                }
            } else {
                DocumentHeaderAction("blocks", m["documentBlocks.view"],
                    selected = editView == DocumentEditView.BLOCKS) { editView = DocumentEditView.BLOCKS }
                DocumentHeaderAction("code", sourceLabel,
                    selected = editView == DocumentEditView.SOURCE) { editView = DocumentEditView.SOURCE }
                DocumentHeaderAction("eye", m["documentPreview"],
                    selected = editView == DocumentEditView.PREVIEW) { editView = DocumentEditView.PREVIEW }
            }
            if (detail != null) DocumentHeaderAction("panel-right", m["libraryDocPanel"],
                selected = view.docsPanel) { view.docsPanel = !view.docsPanel }
        }
        if (!graphReader && (detail != null || draft != null)) {
            val timestamp = detail?.data?.string("updated_at")
            val saveState = when {
                draft?.dirty == true -> m["libraryDocDirty"]
                timestamp != null -> "${m["libraryDocSaved"]} · ${libraryTime(timestamp, m.language)}"
                else -> m["libraryDocSaved"]
            }
            FlowRow(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = StudioTokens.panePad,
                vertical = StudioTokens.space1), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                val canAdjust = api != null && !tab.acting
                DocumentReadingStepper(m["docReading.textSize"], "${textSize.roundToInt()}",
                    canAdjust && textSize > 10f, canAdjust && textSize < 28f,
                    canAdjust && reading?.string("font_size") != null, m["docReading.reset"],
                    decrease = { changeReading("font_size", JsonPrimitive((textSize.roundToInt() - 1).coerceAtLeast(10))) },
                    increase = { changeReading("font_size", JsonPrimitive((textSize.roundToInt() + 1).coerceAtMost(28))) },
                    reset = { changeReading("font_size", null) })
                val step = (leading * 10).roundToInt()
                val multiplier = remember(m.language, step) {
                    NumberFormat.getNumberInstance(Locale.forLanguageTag(m.language)).apply {
                        minimumFractionDigits = 1
                        maximumFractionDigits = 1
                    }.format(step / 10.0)
                }
                DocumentReadingStepper(m["docReading.lineHeight"], "${multiplier}×",
                    canAdjust && step > 10, canAdjust && step < 25, canAdjust && reading?.string("line_height") != null,
                    m["docReading.reset"],
                    decrease = { changeReading("line_height", JsonPrimitive((step - 1) / 10.0)) },
                    increase = { changeReading("line_height", JsonPrimitive((step + 1) / 10.0)) },
                    reset = { changeReading("line_height", null) })
                if (draft != null) {
                    WorkAction(m[if (draft.dirty) "discardDocumentDraft" else "close"], null, enabled = !tab.acting) {
                        tab.documentDrafts.remove(path)
                    }
                    WorkAction(m["saveDocument"], "save", primary = true, enabled = draft.dirty && !tab.acting) {
                        tab.action {
                            put(documentEndpoint(path), draft.payload())
                            tab.documentDrafts.remove(path)
                        }
                    }
                }
                LibraryCaption(saveState, Modifier.align(Alignment.CenterVertically))
            }
        }
        problem?.let { WorkProblemBanner(it) }
        if (graphReader && detail == null) {
            if (problem == null) Centered {
                StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                Muted(m["loading"])
            }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sideWidth = 256.dp.coerceAtMost(maxWidth * .45f)
            val sidePadding = if (maxWidth < 460.dp) StudioTokens.space5 else StudioTokens.space5 + StudioTokens.space5
            val readingRunway = maxHeight * .4f
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onGloballyPositioned {
                    viewportTop = it.positionInRoot().y.roundToInt()
                }, contentAlignment = Alignment.TopCenter) {
                    if (draft != null && editView == DocumentEditView.BLOCKS) {
                        val editStyle = textStyle.copy(fontSize = textSize.sp, lineHeight = (textSize * leading).sp)
                        CompositionLocalProvider(LocalTextStyle provides editStyle, LocalEditorTextStyle provides editStyle) {
                            DocumentBlockEditor(draft.blocks(), draft::changeBlocks, enabled = !tab.acting,
                                modifier = Modifier.widthIn(max = 1152.dp).fillMaxSize()
                                    .studioArrival(distance = 0.dp, duration = StudioMotion.fast),
                                selectionChanged = recordSelection)
                        }
                    } else if (draft != null && editView == DocumentEditView.SOURCE) {
                        TextArea(
                            value = draft.text,
                            onValueChange = { value ->
                                if (value.selection != draft.text.selection || value.text != draft.text.text) {
                                    recordSelection(value.chatSelectedText())
                                }
                                draft.changeSource(value)
                            },
                            modifier = Modifier.widthIn(max = 1152.dp).fillMaxSize().padding(StudioTokens.space5)
                                .studioArrival(distance = 0.dp, duration = StudioMotion.fast)
                                .semantics { contentDescription = sourceLabel },
                            enabled = !tab.acting,
                            textStyle = textStyle.copy(fontFamily = monospace, fontSize = textSize.sp,
                                lineHeight = (textSize * leading).sp),
                        )
                    } else {
                        Column(Modifier.widthIn(max = 1152.dp).fillMaxSize().verticalScroll(scroll)
                            .then(if (draft != null) Modifier.studioArrival(distance = 0.dp, duration = StudioMotion.fast) else Modifier)
                            .padding(start = sidePadding, end = sidePadding + StudioTokens.space2,
                                top = StudioTokens.space4, bottom = readingRunway)) {
                            ChatReadableSelection(tab, readingPage, active = readingPage.query.path == path) {
                                ProvideMarkdownStyling(imageSourceResolver = DisabledMarkdownImages, markdownStyling = markdownStyle,
                                    markdownBlockRenderer = renderer) {
                                    DocumentProse(blocks, renderer, textSize, leading, onUrlClick = { link ->
                                        val target = runCatching { URI(link) }.getOrNull()
                                        if (target?.host != null && target.userInfo == null && target.scheme?.lowercase() in setOf("http", "https")) {
                                            openBrowser(target, workspace)
                                        } else if (target?.scheme == DOCUMENT_LINK_SCHEME && target.authority == null) {
                                            target.path?.removePrefix("/")?.let(::followDocument)
                                        } else if (target != null && !target.isAbsolute && target.authority == null && !target.path.isNullOrBlank()) {
                                            val relative = URI(null, null, "/$path", null).resolve(target).path.removePrefix("/")
                                            followDocument(relative)
                                        }
                                    })
                                }
                            }
                        }
                        VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
                LibrarySidePane(!graphReader && view.docsPanel && detail != null) {
                    Rule(vertical = true)
                    Column(Modifier.width(sideWidth).fillMaxHeight().background(colors.surface)
                        .verticalScroll(rememberScrollState())) {
                        if (rendering) DocumentOutline(outline, activeHeading, enabled = view.docsPanel) { index ->
                            val destination = headingTops[index]?.minus(viewportTop + outlineInset)?.coerceIn(0, scroll.maxValue)
                            if (destination != null) scope.launch {
                                if (reducedMotion) scroll.scrollTo(destination)
                                else scroll.animateScrollTo(destination, tween(StudioMotion.slow, easing = StudioMotion.standard))
                            }
                        }
                        detail?.let { current ->
                            DetailLinks(current, navigate) { target ->
                                val existing = resolveDocumentLink(target, documents)
                                if (existing != null) followDocument(existing)
                                else view.documentAction = DocumentTreeRequest(DocumentTreeAction.CREATE, initialName = target)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveDocumentLink(target: String, documents: List<WorkspaceItem>): String? {
    val wanted = target.trim('/')
    if (wanted.isEmpty()) return null
    return documents.firstOrNull { it.id == wanted }?.id
        ?: documents.firstOrNull { it.id == "$wanted.md" }?.id
        ?: documents.firstOrNull { it.id == "$wanted.markdown" }?.id
        ?: documents.firstOrNull { it.id == "$wanted.txt" }?.id
        ?: documents.firstOrNull { it.id.equals(wanted, ignoreCase = true) ||
            it.id.substringAfterLast('/').substringBeforeLast('.').equals(wanted, ignoreCase = true) }?.id
        ?: documents.firstOrNull { it.title.equals(wanted, ignoreCase = true) }?.id
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentHeaderAction(icon: String, label: String, enabled: Boolean = true, selected: Boolean? = null,
                                 onClick: () -> Unit) {
    StudioTooltip(label) {
        val state = if (selected == null) Modifier else Modifier.semantics { this.selected = selected }
        StudioAction(label, icon, state, enabled, primary = false, outlined = true, iconOnly = true,
            danger = false, busy = false, onClick = onClick, selected = selected == true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentReadingStepper(
    label: String, value: String, canDecrease: Boolean, canIncrease: Boolean, canReset: Boolean,
    resetLabel: String, decrease: () -> Unit, increase: () -> Unit, reset: () -> Unit,
) {
    val colors = localColors.current
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    Row(Modifier.border(StudioTokens.borderWidth, colors.controlBorder, shape),
        verticalAlignment = Alignment.CenterVertically) {
        StudioTooltip("$label: −") {
            StudioIconButton("$label: −", "minus", decrease, enabled = canDecrease)
        }
        StudioTooltip("$label: $value · $resetLabel") {
            WorkAction(value, null, Modifier.semantics { contentDescription = "$label: $value · $resetLabel" },
                enabled = canReset, outlined = false, onClick = reset)
        }
        StudioTooltip("$label: +") {
            StudioIconButton("$label: +", "plus", increase, enabled = canIncrease)
        }
    }
}

@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
private fun DocumentProse(blocks: List<MarkdownBlock>, renderer: DocumentBlockRenderer, size: Float, leading: Float,
                           onUrlClick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((size * .75f).dp)) {
        blocks.forEachIndexed { index, block ->
            key(index, block) {
                when (block) {
                    is MarkdownBlock.CodeBlock -> DocumentCode(block.content, size, leading)
                    // A wide table has its own scrolling viewport; it never widens prose or the sidebar.
                    is MarkdownBlock.CustomBlock -> {
                        val scroll = rememberScrollState()
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                                renderer.RenderBlock(block, true, onUrlClick, Modifier)
                            }
                            if (scroll.maxValue in 1 until Int.MAX_VALUE) {
                                HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxWidth())
                            }
                        }
                    }
                    else -> renderer.RenderBlock(block, true, onUrlClick, Modifier)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@Composable
private fun DocumentCode(code: String, size: Float, leading: Float) {
    val m = localMessages.current
    val colors = localColors.current
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    Box(Modifier.fillMaxWidth().clip(shape).background(colors.inset)
        .border(StudioTokens.borderWidth, colors.subtleBorder, shape)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(code, Modifier.weight(1f).horizontalScroll(scroll).padding(StudioTokens.space3),
                    color = colors.text, fontFamily = localStudioMono.current, softWrap = false,
                    fontSize = (size * .85f).sp, lineHeight = (size * .85f * leading).sp)
                Box(Modifier.padding(top = StudioTokens.space1, end = StudioTokens.space1)) {
                    StudioTooltip(m["libraryCopy"]) {
                        StudioIconButton(m["libraryCopy"], "copy", { clipboard.setText(AnnotatedString(code)) })
                    }
                }
            }
            if (scroll.maxValue in 1 until Int.MAX_VALUE) {
                HorizontalScrollbar(rememberScrollbarAdapter(scroll),
                    Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space3))
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun DocumentOutline(headings: List<DocumentHeading>, active: Int?, enabled: Boolean, open: (Int) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    Column(Modifier.fillMaxWidth().padding(StudioTokens.space3), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
        DocumentSideHeader("list-tree", m["libraryDocOutline"])
        if (headings.isEmpty()) Text(m["libraryNoHeadings"], color = colors.faint)
        headings.forEachIndexed { index, heading ->
            val current = active == index
            StudioSelectionRow(current, { open(index) }, Modifier.fillMaxWidth(), enabled, role = Role.Button) {
                Text(heading.title, Modifier.padding(start = StudioTokens.space1 * (heading.block.level - 1)),
                    color = if (current) colors.onAccentContainer else if (heading.block.level == 1) colors.text else colors.muted,
                    fontWeight = if (heading.block.level == 1) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    DocumentSideRule()
}

@Composable
private fun DocumentSideHeader(icon: String, label: String) {
    val colors = localColors.current
    val language = localMessages.current.language
    Row(Modifier.padding(bottom = StudioTokens.space2), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        StudioIcon(icon, Modifier.size(StudioTokens.compactIconSize), colors.faint)
        Text(label.uppercase(java.util.Locale.forLanguageTag(language)), color = colors.faint,
            fontSize = StudioTokens.textSmall)
    }
}

@Composable
private fun DocumentSideRule() {
    Box(Modifier.fillMaxWidth().height(StudioTokens.borderWidth).background(localColors.current.subtleBorder))
}

// Block editor spec §4.10: share the prose scale and neutral surface roles with the SPA.
@OptIn(ExperimentalJewelApi::class)
private fun documentMarkdownStyle(colors: StudioColors, face: TextStyle, size: Float, leading: Float,
                                  monospace: FontFamily): MarkdownStyling {
    val body = face.copy(color = colors.text, fontSize = size.sp, lineHeight = (size * leading).sp)
    val code = face.copy(color = colors.text, fontFamily = monospace,
        fontSize = (size * .85f).sp, lineHeight = (size * leading * .85f).sp)
    val link = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)
    fun inlines(style: TextStyle): InlinesStyling {
        val inlineCode = code.toSpanStyle().copy(background = colors.inset, fontSize = style.fontSize * .9f)
        return if (colors.dark) InlinesStyling.dark(style, code, inlineCode, link = link)
            else InlinesStyling.light(style, code, inlineCode, link = link)
    }
    fun heading(scale: Float): InlinesStyling {
        val font = size * scale
        return inlines(face.copy(color = if (scale > 1f) colors.text else colors.secondary,
            fontSize = font.sp, lineHeight = (font * 1.3f).sp, fontWeight = FontWeight.SemiBold))
    }
    val gap = (size * .75f).dp
    val headingGap = (size * .6f).dp
    val h = Heading(
        Heading.H1(heading(1.7f), 0.dp, colors.subtleBorder, 0.dp, PaddingValues()),
        Heading.H2(heading(1.35f), StudioTokens.borderWidth, colors.subtleBorder, (size * .34f).dp, PaddingValues(top = headingGap)),
        Heading.H3(heading(1.15f), 0.dp, colors.subtleBorder, 0.dp, PaddingValues(top = headingGap)),
        Heading.H4(heading(1f), 0.dp, colors.subtleBorder, 0.dp, PaddingValues(top = headingGap)),
        Heading.H5(heading(1f), 0.dp, colors.subtleBorder, 0.dp, PaddingValues(top = headingGap)),
        Heading.H6(heading(1f), 0.dp, colors.subtleBorder, 0.dp, PaddingValues(top = headingGap)),
    )
    val block = MarkdownStyling.Code(
        MarkdownStyling.Code.Indented(code, PaddingValues(StudioTokens.space3), RoundedCornerShape(StudioTokens.radiusMd),
            colors.inset, StudioTokens.borderWidth, colors.subtleBorder, fillWidth = true, scrollsHorizontally = true),
        MarkdownStyling.Code.Fenced(code, PaddingValues(StudioTokens.space3), RoundedCornerShape(StudioTokens.radiusMd),
            colors.inset, StudioTokens.borderWidth, colors.subtleBorder, fillWidth = true, scrollsHorizontally = true,
            infoTextStyle = code, infoPadding = PaddingValues(),
            infoPosition = MarkdownStyling.Code.Fenced.InfoPosition.Hide),
    )
    return if (colors.dark) MarkdownStyling.dark(body, code, inlines(body), gap, heading = h, code = block)
        else MarkdownStyling.light(body, code, inlines(body), gap, heading = h, code = block)
}

@Composable
private fun DetailLinks(detail: WorkspaceDetail, navigate: (Page) -> Unit, createLinked: (String) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    Box(Modifier.padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space2)) {
        WorkAction(m["documentGraph"], "waypoints") {
            navigate(Page("/documents/graph", Selectors(path = detail.item.id)))
        }
    }
    DocumentSideRule()
    for ((field, label) in listOf("links" to "documentLinks", "backlinks" to "backlinks")) {
        val entries = detail.data.objects(field).filter { it.string("path") != null }.distinctBy { it.string("path") }
        Column(Modifier.fillMaxWidth().padding(StudioTokens.space3), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            DocumentSideHeader(if (field == "backlinks") "corner-up-left" else "link-2", m[label])
            if (entries.isEmpty()) Text(m[if (field == "backlinks") "libraryNoBacklinks" else "libraryNoDocLinks"],
                color = colors.faint)
            entries.forEach { doc ->
                val path = requireNotNull(doc.string("path"))
                Column {
                    WorkAction(doc.string("title") ?: doc.string("label") ?: doc.string("target") ?: path, null,
                        outlined = false) { navigate(Page("/documents", Selectors(path = path))) }
                    (doc["contexts"] as? JsonArray).orEmpty().forEach { context ->
                        val value = (context as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        LibraryCaption(value, Modifier.padding(start = StudioTokens.rowPadX))
                    }
                }
            }
        }
        DocumentSideRule()
    }
    val unresolved = detail.data.objects("links").filterNot { it.flag("resolved") }
        .mapNotNull { it.string("target")?.takeUnless(String::isBlank) }.distinct()
    if (unresolved.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(StudioTokens.space3), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            DocumentSideHeader("link-2", m["libraryDocUnresolved"])
            for (target in unresolved) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(target, Modifier.weight(1f), fontFamily = localStudioMono.current,
                        fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    WorkAction(m["create"], null) { createLinked(target) }
                }
            }
        }
    }
}
