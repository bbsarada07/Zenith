package com.zenith.engine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * NodeTreeSerializer: Ultra-low latency Accessibility Node Tree Serializer & State Hasher.
 *
 * Capabilities:
 * 1. Recursive Tree Crawler: Extracts flat, actionable UI attributes (text, description, id, bounds)
 *    with a strict depth limit of 15 to prevent ART stack overflow on complex View hierarchies.
 * 2. Minimal JSON Serialization: Drops heavy Java reflection/object graphs, creating compact JSON payloads.
 * 3. Fast State Hashing: Generates SHA-256 fingerprint of current top-level package and visible text nodes
 *    for instantaneous Reflection Loop mutation checks.
 * 4. Pass 1 Semantic Search: Finds matching nodes by exact text, content description, or resource ID.
 */
object NodeTreeSerializer {

    private const val TAG = "NodeTreeSerializer"
    private const val MAX_CRAWL_DEPTH = 15

    data class SimplifiedNode(
        val text: String,
        val contentDescription: String,
        val resourceId: String,
        val className: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isEnabled: Boolean,
        val isFocused: Boolean,
        val isSelected: Boolean,
        val isScrollable: Boolean,
        val depth: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("text", text)
            put("contentDescription", contentDescription)
            put("resourceId", resourceId)
            put("className", className)
            put("bounds", JSONArray().apply {
                put(bounds.left)
                put(bounds.top)
                put(bounds.right)
                put(bounds.bottom)
            })
            put("isClickable", isClickable)
            put("isEditable", isEditable)
            put("isEnabled", isEnabled)
            put("isFocused", isFocused)
            put("isSelected", isSelected)
            put("isScrollable", isScrollable)
            put("depth", depth)
        }
    }

    /**
     * Crawls the active AccessibilityNodeInfo tree and returns a flat list of simplified actionable nodes.
     */
    fun extractSimplifiedNodes(root: AccessibilityNodeInfo?): List<SimplifiedNode> {
        if (root == null) return emptyList()
        val nodes = mutableListOf<SimplifiedNode>()
        crawlNodeRecursive(root, 0, nodes)
        return nodes
    }

    private fun crawlNodeRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        outList: MutableList<SimplifiedNode>
    ) {
        if (depth > MAX_CRAWL_DEPTH) return

        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val resourceId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Include only nodes that have visible text, descriptions, are clickable/editable, or contain interactable children
        val isActionable = text.isNotEmpty() ||
                contentDesc.isNotEmpty() ||
                resourceId.isNotEmpty() ||
                node.isClickable ||
                node.isEditable ||
                node.isScrollable

        if (isActionable && (bounds.width() > 0 && bounds.height() > 0)) {
            outList.add(
                SimplifiedNode(
                    text = text,
                    contentDescription = contentDesc,
                    resourceId = resourceId,
                    className = className,
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isEnabled = node.isEnabled,
                    isFocused = node.isFocused,
                    isSelected = node.isSelected,
                    isScrollable = node.isScrollable,
                    depth = depth
                )
            )
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                crawlNodeRecursive(child, depth + 1, outList)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    /**
     * Converts a list of SimplifiedNode items into a minimal JSON Array.
     */
    fun toJsonArray(nodes: List<SimplifiedNode>): JSONArray {
        val array = JSONArray()
        for (node in nodes) {
            array.put(node.toJson())
        }
        return array
    }

    /**
     * Computes a deterministic SHA-256 fingerprint of the current app package and visible text/bounds.
     */
    fun computeStateHash(packageName: String, nodes: List<SimplifiedNode>): String {
        val sb = StringBuilder()
        sb.append(packageName).append("|")
        for (node in nodes) {
            if (node.text.isNotEmpty() || node.contentDescription.isNotEmpty()) {
                sb.append(node.text)
                    .append(":")
                    .append(node.contentDescription)
                    .append(":[")
                    .append(node.bounds.left).append(",").append(node.bounds.top).append("]|")
            }
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(sb.toString().toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error computing state hash: ${e.message}")
            sb.toString().hashCode().toString()
        }
    }

    /**
     * Pass 1 (Semantic): Searches AccessibilityNodeInfo tree for exact text, content description, or ID match.
     * Returns matching raw node (caller must recycle) or null if not found.
     */
    fun findSemanticNode(
        root: AccessibilityNodeInfo?,
        targetQuery: String,
        matchType: String = "AUTO"
    ): AccessibilityNodeInfo? {
        if (root == null || targetQuery.isBlank()) return null
        return searchNodeRecursive(root, targetQuery.trim(), matchType.uppercase(), 0)
    }

    private fun searchNodeRecursive(
        node: AccessibilityNodeInfo,
        target: String,
        matchType: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > MAX_CRAWL_DEPTH) return null

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName

        val isMatch = when (matchType) {
            "TEXT" -> text?.equals(target, ignoreCase = true) == true
            "DESC" -> desc?.equals(target, ignoreCase = true) == true
            "ID" -> resId?.contains(target, ignoreCase = true) == true
            else -> {
                text?.equals(target, ignoreCase = true) == true ||
                        desc?.equals(target, ignoreCase = true) == true ||
                        (text?.contains(target, ignoreCase = true) == true && target.length >= 3) ||
                        (desc?.contains(target, ignoreCase = true) == true && target.length >= 3) ||
                        resId?.contains(target, ignoreCase = true) == true
            }
        }

        if (isMatch) {
            return node
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeRecursive(child, target, matchType, depth + 1)
            if (found != null) {
                return found
            }
            @Suppress("DEPRECATION")
            child.recycle()
        }

        return null
    }
}
