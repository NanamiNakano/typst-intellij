package dev.thynanami.idea.typst.lsp.outline

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import dev.thynanami.idea.typst.lsp.DocumentOutline
import dev.thynanami.idea.typst.lsp.OutlineItem
import dev.thynanami.idea.typst.lsp.TinymistLanguageServer
import dev.thynanami.idea.typst.lsp.TinymistLanguageServerDescriptor
import dev.thynanami.idea.typst.lsp.scrollRequests
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class TypstOutlineToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = TypstOutlinePanel(project)
        val content = ContentFactory.getInstance().createContent(view, "", false)
        content.setDisposer(view)
        toolWindow.contentManager.addContent(content)
    }
}

private class TypstOutlinePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val tree = Tree(DefaultTreeModel(outlineTreeRoot(DocumentOutline()))).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        emptyText.text = "No outline available"
        accessibleContext.accessibleName = "Typst outline"
        cellRenderer = OutlineRenderer()
    }

    init {
        TreeSpeedSearch.installOn(tree, true) { path ->
            ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? OutlineItem)?.title.orEmpty()
        }
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)

        tree.addMouseListener(OutlineMouseListener())
        tree.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "navigateOutline")
        tree.actionMap.put("navigateOutline", NavigateOutlineAction())
    }

    private val updates = project.service<TypstOutlineModel>().observe(::update)

    fun update(outline: DocumentOutline) {
        val expandedKeys = tree.expandedPaths.mapNotNull { (it.lastPathComponent as? OutlineNode)?.key }.toSet()
        val selectedKey = (tree.selectionPath?.lastPathComponent as? OutlineNode)?.key
        val root = outlineTreeRoot(outline)
        tree.model = DefaultTreeModel(root)
        tree.expandPath(TreePath(root))
        root.preorderEnumeration().asIterator().forEachRemaining { node ->
            if (node is OutlineNode) {
                val path = TreePath(node.path)
                if (node.key in expandedKeys) tree.expandPath(path)
                if (node.key == selectedKey) tree.selectionPath = path
            }
        }
    }

    private fun navigate(path: TreePath?) {
        val item = (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? OutlineItem ?: return
        if (item.scrollRequests().isNotEmpty() && !project.isDisposed) {
            val descriptor = project.service<TinymistLanguageServer>().running()?.descriptor
                as? TinymistLanguageServerDescriptor
            descriptor?.navigateOutline(item)
        }
    }

    override fun dispose() {
        updates.cancel()
    }

    private inner class OutlineMouseListener : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                navigate(tree.getPathForLocation(event.x, event.y))
            }
        }
    }

    private inner class NavigateOutlineAction : AbstractAction() {
        override fun actionPerformed(event: ActionEvent?) = navigate(tree.selectionPath)
    }
}

private fun outlineTreeRoot(outline: DocumentOutline): DefaultMutableTreeNode {
    fun append(parent: DefaultMutableTreeNode, items: List<OutlineItem>, parentKey: List<OutlinePathElement>) {
        val occurrences = mutableMapOf<String, Int>()
        items.forEach { item ->
            val occurrence = occurrences.getOrDefault(item.title, 0)
            occurrences[item.title] = occurrence + 1
            val key = parentKey + OutlinePathElement(item.title, occurrence)
            val node = OutlineNode(item, key)
            parent.add(node)
            append(node, item.children, key)
        }
    }

    return DefaultMutableTreeNode().apply {
        append(this, outline.items, emptyList())
    }
}

private data class OutlinePathElement(val title: String, val occurrence: Int)

private class OutlineNode(item: OutlineItem, val key: List<OutlinePathElement>) : DefaultMutableTreeNode(item) {
    override fun toString(): String = (userObject as OutlineItem).title
}

private class OutlineRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val item = (value as? DefaultMutableTreeNode)?.userObject as? OutlineItem ?: return
        append(item.title)
        item.position?.let { append("  Page ${it.pageNo}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }
}
