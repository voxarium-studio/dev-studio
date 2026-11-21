package studio.voxarium.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.flow.collect

private const val MAX_CHAT_SELECTION = 16_000

// §10.9: selected text is ephemeral and scopes to the page that provided it.
// Changing focus to the composer keeps the selection. An explicit click in the reader clears it.
internal class ChatContextSelection {
    private var selected by mutableStateOf<Pair<Page, String>?>(null)

    fun text(page: Page) = selected?.takeIf { it.first == page }?.second

    fun update(page: Page, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) clear(page)
        else {
            var end = minOf(trimmed.length, MAX_CHAT_SELECTION)
            if (end < trimmed.length && trimmed[end - 1].isHighSurrogate()) end--
            selected = page to trimmed.substring(0, end)
        }
    }

    fun clear(page: Page, sent: String? = null) {
        if (selected?.first == page && (sent == null || selected?.second == sent)) selected = null
    }
}

internal fun TextFieldValue.chatSelectedText(): String {
    return text.substring(selection.min, selection.max)
}

@Composable
internal fun ChatReadableSelection(
    tab: ProjectTab,
    page: Page,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    content: @Composable () -> Unit,
) {
    key(page) {
        val state = rememberSelectionState()
        LaunchedEffect(tab, page, state, active) {
            snapshotFlow { state.selectedTexts }.collect { parts ->
                if (active && tab.page == page && parts.isNotEmpty()) {
                    val selection = buildString {
                        for (part in parts) {
                            if (length > 0) append('\n')
                            val room = MAX_CHAT_SELECTION + 1 - length
                            if (room <= 0) break
                            append(part.text, 0, minOf(room, part.length))
                            if (length > MAX_CHAT_SELECTION) break
                        }
                    }
                    if (selection.isNotBlank()) tab.chatSelection.update(page, selection)
                }
            }
        }
        SelectionContainer(state, modifier.pointerInput(tab, page) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (active) tab.chatSelection.clear(page)
            }
        }, content)
    }
}
