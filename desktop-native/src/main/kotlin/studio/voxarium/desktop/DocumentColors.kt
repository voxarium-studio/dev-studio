package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color

// The palette values below are persisted Markdown colors, not Studio chrome tokens.
internal enum class DocumentColor(val word: String, private val dark: Long, private val light: Long) {
    RED("red", 0xfff85149, 0xffcd222e), ORANGE("orange", 0xfff0883e, 0xffb34800),
    YELLOW("yellow", 0xffe3b341, 0xff8f6000), GREEN("green", 0xff3fb950, 0xff197a35),
    CYAN("cyan", 0xff39c5cf, 0xff096f7b), BLUE("blue", 0xff58a6ff, 0xff0a66d0),
    PURPLE("purple", 0xffbc8cff, 0xff7547b6), PINK("pink", 0xfff778ba, 0xffb52d77),
    BROWN("brown", 0xffb98257, 0xff85552a), GRAY("gray", 0xffa0a4ab, 0xff626871);

    fun tint(isDark: Boolean) = Color(if (isDark) dark else light)
}

internal data class DocumentColorSpan(val start: Int, val close: Int, val end: Int, val color: DocumentColor)

private val colorSuffix = Regex("\\{\\.([a-z]+)}")
private val tickRun = Regex("`+")

// Block editor spec §3.2: bracketed Pandoc spans use a closed palette and keep their source bytes.
internal fun documentColorSpans(source: String): List<DocumentColorSpan> = buildList {
    val open = ArrayDeque<Int>()
    var index = 0
    while (index < source.length) {
        when (source[index]) {
            '\\' -> if (source.getOrNull(index + 1) !in listOf('\r', '\n')) index++
            '\r', '\n' -> open.clear()
            '`' -> {
                val opening = tickRun.matchAt(source, index)!!
                val remaining = tickRun.findAll(source, opening.range.last + 1)
                val nextLine = source.indexOfAny(charArrayOf('\r', '\n'), opening.range.last + 1)
                val close = remaining.takeWhile { nextLine < 0 || it.range.first < nextLine }
                    .firstOrNull { it.value.length == opening.value.length }
                index = (close ?: opening).range.last
            }
            '[' -> open.addLast(index)
            ']' -> {
                val start = open.removeLastOrNull()
                val suffix = if (start != null && index > start + 1) colorSuffix.matchAt(source, index + 1) else null
                val word = suffix?.groupValues?.get(1)
                val color = DocumentColor.entries.firstOrNull { it.word == word || it == DocumentColor.GRAY && word == "grey" }
                if (start != null && suffix != null && color != null) {
                    add(DocumentColorSpan(start, index, suffix.range.last + 1, color))
                }
            }
        }
        index++
    }
}

/** Keep document colors unmodified inside an accessible, theme-painted palette target. */
@Composable
internal fun DocumentColorSwatch(
    label: String, tint: Color, selected: Boolean, enabled: Boolean = true, icon: String? = null, onClick: () -> Unit,
) {
    val colors = localColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val face = studioColor(when {
        selected -> colors.selected
        hovered -> colors.hover
        else -> colors.surface
    })
    StudioTooltip(label) {
        Box(Modifier.size(StudioTokens.controlMinHeight)
            .graphicsLayer { alpha = if (enabled) 1f else StudioTokens.disabledOpacity }
            .studioFocusRing(CircleShape, enabled, face).background(face, CircleShape)
            .border(StudioTokens.borderWidth,
                if (selected) colors.focusOn(face) else colors.controlBorder, CircleShape)
            .hoverable(interaction, enabled)
            .selectable(selected, interaction, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            if (icon == null) Box(Modifier.size(StudioTokens.iconSize).background(tint, CircleShape))
            else StudioIcon(icon, Modifier.size(StudioTokens.iconSize), StudioTokens.ensureContrast(tint, face))
        }
    }
}
