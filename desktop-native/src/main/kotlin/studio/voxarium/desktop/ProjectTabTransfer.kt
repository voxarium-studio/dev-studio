@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package studio.voxarium.desktop

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import javax.swing.SwingUtilities

private data class ProjectTabTransfer(val workspace: Workspace, val window: WorkspaceWindow, val tab: ProjectTab)

private val projectTabFlavor = DataFlavor("${DataFlavor.javaJVMLocalObjectMimeType};class=${ProjectTabTransfer::class.java.name}")

// §5.3.4: the payload never leaves this JVM. Drop only moves the registered tab.
internal fun Modifier.projectTabDrag(workspace: Workspace, source: WorkspaceWindow, tab: ProjectTab): Modifier =
    dragAndDropSource {
        if (workspace.quitting || workspace.confirmation != null || tab.binding == null || tab !in source.tabs) {
            null
        } else {
            val data = ProjectTabTransfer(workspace, source, tab)
            val transferable = object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(projectTabFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == projectTabFlavor
                override fun getTransferData(flavor: DataFlavor): Any =
                    if (isDataFlavorSupported(flavor)) data else throw UnsupportedFlavorException(flavor)
            }
            DragAndDropTransferData(DragAndDropTransferable(transferable), supportedActions = listOf(DragAndDropTransferAction.Move))
        }
    }

@Composable
internal fun Modifier.projectTabDrop(workspace: Workspace, target: WorkspaceWindow, before: ProjectTab? = null): Modifier {
    var hovered by remember(workspace, target, before) { mutableStateOf(false) }
    val accent = localColors.current.accent
    val receiver = remember(workspace, target, before) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovered = true }
            override fun onExited(event: DragAndDropEvent) { hovered = false }
            override fun onEnded(event: DragAndDropEvent) { hovered = false }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovered = false
                val transfer = runCatching { event.awtTransferable.getTransferData(projectTabFlavor) }.getOrNull() as? ProjectTabTransfer
                    ?: return false
                if (transfer.workspace !== workspace || workspace.confirmation != null || workspace.quitting ||
                    transfer.window !in workspace.windows || transfer.tab !in transfer.window.tabs ||
                    target !in workspace.windows || before === transfer.tab || before != null && before !in target.tabs) return false
                SwingUtilities.invokeLater { workspace.move(transfer.window, target, transfer.tab, before) }
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { event ->
        !workspace.quitting && workspace.confirmation == null && event.awtTransferable.isDataFlavorSupported(projectTabFlavor)
    }, target = receiver).drawBehind {
        if (hovered) {
            val inset = StudioTokens.space1.toPx()
            val stroke = StudioTokens.focusWidth.toPx()
            drawLine(accent, Offset(stroke, inset), Offset(stroke, size.height - inset), strokeWidth = stroke)
        }
    }
}
