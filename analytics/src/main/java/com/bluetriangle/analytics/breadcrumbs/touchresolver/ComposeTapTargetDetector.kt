@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.bluetriangle.analytics.breadcrumbs.touchresolver

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.node.LayoutNodeLayoutDelegate
import androidx.compose.ui.node.Owner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.lang.reflect.Method
import java.util.LinkedList

internal class ComposeTapTargetDetector {

    private val getLayoutDelegate: Method? = getLayoutDelegateMethod()

    private fun getLayoutDelegateMethod(): Method? {
        return try {
            // If the compiler sees literal string "getLayoutDelegate" used in reflection
            // it'll try to optimize it by changing the reflection with actual code
            // which defeats our purpose as we need to find a method named "getLayoutDelegate" which would
            // have @<module_name> attached to it (as it's marked with internal modifier) @see https://4comprehension.com/kotlins-internal-visibility-modifier-and-java-interoperability/ and we don't know the module name. (it changed after 1.10x)
            // so we are trying to find a method that starts with getLayoutDelegate regardless of the @<module_name> suffix
            val methodName = buildString {
                append("getLayout")
                append("Delegate")
            }
            LayoutNode::class.java.methods.firstOrNull { method ->
                method.name.startsWith(methodName)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun nodeToName(node: LayoutNode): String? =
        try {
            getNodeName(node)
        } catch (_: Throwable) {
            null
        }

    fun findTapTarget(
        owner: Owner,
        x: Float,
        y: Float,
    ): LayoutNode? {
        val queue = LinkedList<LayoutNode>()
        queue.addFirst(owner.root)
        var target: LayoutNode? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isPlaced && hitTest(node, x, y)) {
                target = node
            }

            queue.addAll(node.zSortedChildren.asMutableList())
        }
        return target
    }

    private fun isValidClickTarget(node: LayoutNode): Boolean {
        for (info in node.getModifierInfo()) {
            val modifier = info.modifier
            if (modifier is SemanticsModifier) {
                with(modifier.semanticsConfiguration) {
                    if (contains(SemanticsActions.OnClick)) {
                        return true
                    }
                }
            } else {
                val className = modifier::class.qualifiedName
                if (
                    className == CLASS_NAME_CLICKABLE_ELEMENT ||
                    className == CLASS_NAME_COMBINED_CLICKABLE_ELEMENT ||
                    className == CLASS_NAME_TOGGLEABLE_ELEMENT
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun getNodeName(node: LayoutNode): String? {
        for (info in node.getModifierInfo()) {
            val modifier = info.modifier
            val trackedName = modifier.getTrackedName()
            if (trackedName != null) {
                return trackedName
            }
            else if (modifier is SemanticsModifier) {
                with(modifier.semanticsConfiguration) {
                    val onClickSemanticsConfiguration = getOrNull(SemanticsActions.OnClick)
                    if (onClickSemanticsConfiguration != null) {
                        val accessibilityActionLabel = onClickSemanticsConfiguration.label
                        if (accessibilityActionLabel != null) {
                            return accessibilityActionLabel
                        }
                    }

                    val contentDescriptionSemanticsConfiguration = getOrNull(SemanticsProperties.ContentDescription)
                    if (contentDescriptionSemanticsConfiguration != null) {
                        val contentDescription =
                            contentDescriptionSemanticsConfiguration.getOrNull(0)
                        if (contentDescription != null) {
                            return contentDescription
                        }
                    }
                }
            }
        }

        return null
    }

    private fun getNodeText(node: LayoutNode, checkClickable: Boolean = true): String? {
        if (checkClickable && !node.isClickable)
            return null

        // Check if the current layout node contains text properties
        val currentText = getTextFromNode(node)
        if (currentText != null)
            return currentText

        // Fallback: Loop down into children ordered by visual Z-index
        // This is vital for complex components like Buttons wrapping a Text item.
        for (child in node.zSortedChildren.asMutableList()) {
            val childText = getNodeText(child, false)
            if (childText != null)
                return childText
        }

        return null
    }

    private fun getTextFromNode(node: LayoutNode): String? {
        // collapsedSemantics automatically flattens old modifiers
        // and modern Modifier.Node API structures into one target object.
        val config = node.collapsedSemantics ?: return null

        with(config) {
            // Priority 1: Check actual Text content (e.g., Text/TextField composables)
            val textList = getOrNull(SemanticsProperties.Text)
            if (!textList.isNullOrEmpty()) {
                return textList.joinToString(separator = " ") { it.text }
            }

            // Priority 2: Check Accessible Content Description labels (e.g., Icon labels)
            val contentDescList = getOrNull(SemanticsProperties.ContentDescription)
            if (!contentDescList.isNullOrEmpty()) {
                return contentDescList.joinToString(separator = " ")
            }

            // Priority 3: Check if an OnClick accessibility action label was explicitly set
            val onClickAction = getOrNull(SemanticsActions.OnClick)
            if (onClickAction?.label != null) {
                return onClickAction.label
            }
        }

        return null
    }

    private fun getLayoutNodeBoundsInWindow(node: LayoutNode): Rect? =
        try {
            node.layoutDelegate.outerCoordinator.coordinates
                .boundsInWindow()
        } catch (_: Throwable) {
            getLayoutNodeBoundsInWindowThroughReflection(node)
        }

    private fun getLayoutNodeBoundsInWindowThroughReflection(node: LayoutNode): Rect? = try {
            (getLayoutDelegate?.invoke(node) as? LayoutNodeLayoutDelegate)?.outerCoordinator?.coordinates?.boundsInWindow()
        } catch (_: Throwable) {
            null
        }

    private fun hitTest(
        node: LayoutNode,
        x: Float,
        y: Float,
    ): Boolean {
        val bounded =
            getLayoutNodeBoundsInWindow(node)?.let { bounds ->
                x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
            } == true

        return bounded && isValidClickTarget(node)
    }

    companion object {
        private const val CLASS_NAME_CLICKABLE_ELEMENT =
            "androidx.compose.foundation.ClickableElement"
        private const val CLASS_NAME_COMBINED_CLICKABLE_ELEMENT =
            "androidx.compose.foundation.CombinedClickableElement"
        private const val CLASS_NAME_TOGGLEABLE_ELEMENT =
            "androidx.compose.foundation.selection.ToggleableElement"
    }
}

private val LayoutNode.isClickable: Boolean
    get() {
        val config = collapsedSemantics ?: return false
        val role = config.getOrNull(SemanticsProperties.Role)

        return role == Role.Button ||
                role == Role.Tab ||
                config.contains(SemanticsActions.OnClick)
    }

private val LayoutNode.isInteractive: Boolean
    get() {
        val config = collapsedSemantics ?: return false
        val role = config.getOrNull(SemanticsProperties.Role)

        return role == Role.Button ||
                role == Role.Checkbox ||
                role == Role.RadioButton ||
                role == Role.Switch ||
                role == Role.Tab ||
                config.contains(SemanticsActions.OnClick)
    }