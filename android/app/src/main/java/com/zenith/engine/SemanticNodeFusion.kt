package com.zenith.engine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * SemanticNodeFusion: Multi-Modal Viewport Semantic Tree & Accessibility Fusion Engine.
 *
 * Captures live Android AccessibilityNodeInfo UI hierarchies and merges them with Google ML Kit
 * OCR spatial geometry blocks to build a unified, high-fidelity semantic graph of the screen.
 */
object SemanticNodeFusion {

    private const val TAG = "SemanticNodeFusion"
    private const val OVERLAP_THRESHOLD = 0.80f

    data class SemanticElement(
        val id: String,
        val text: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val className: String,
        val nodeSource: String // "ACCESSIBILITY", "OCR", or "FUSED"
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("text", text)
                put("isClickable", isClickable)
                put("isEditable", isEditable)
                put("isScrollable", isScrollable)
                put("className", className)
                put("nodeSource", nodeSource)

                val boundsObj = JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                    put("centerX", bounds.centerX())
                    put("centerY", bounds.centerY())
                    put("width", bounds.width())
                    put("height", bounds.height())
                }
                put("bounds", boundsObj)
            }
        }
    }

    /**
     * Traverses the active Android View hierarchy via [ZenithAccessibilityService]
     * and extracts all visible semantic nodes in physical screen coordinates.
     */
    fun captureSemanticTree(): List<SemanticElement> {
        val rootNode = ZenithAccessibilityService.instance?.rootInActiveWindow ?: run {
            Log.w(TAG, "Root accessibility node unavailable. Ensure ZenithAccessibilityService is running.")
            return emptyList()
        }

        val result = mutableListOf<SemanticElement>()
        try {
            traverseNode(rootNode, result)
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree: ${e.message}", e)
        }
        return result
    }

    private fun traverseNode(node: AccessibilityNodeInfo, outputList: MutableList<SemanticElement>) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val className = node.className?.toString() ?: "android.view.View"
        val viewId = node.viewIdResourceName ?: "a11y_${outputList.size}_${UUID.randomUUID().toString().take(6)}"

        // Filter out empty ghost containers with zero dimensions
        val hasGeometry = bounds.width() > 0 && bounds.height() > 0
        val hasContentOrAction = text.isNotBlank() || isClickable || isEditable || isScrollable

        if (hasGeometry && hasContentOrAction) {
            outputList.add(
                SemanticElement(
                    id = viewId,
                    text = text,
                    bounds = bounds,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isScrollable = isScrollable,
                    className = className,
                    nodeSource = "ACCESSIBILITY"
                )
            )
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, outputList)
        }
    }

    /**
     * Fuses optical character recognition blocks with raw accessibility elements.
     * When an OCR block intersects an accessibility node bounds by >= 80%, their
     * properties are combined into a single authoritative "FUSED" element.
     */
    fun fuse(
        ocrBlocks: List<SpatialVisionEngine.RecognizedBlock>,
        accessibilityNodes: List<SemanticElement>
    ): List<SemanticElement> {
        val fusedElements = mutableListOf<SemanticElement>()
        val matchedOcrIndices = mutableSetOf<Int>()
        val matchedA11yIndices = mutableSetOf<Int>()

        // 1. Check for spatial intersections >= 80%
        for (aIndex in accessibilityNodes.indices) {
            val aNode = accessibilityNodes[aIndex]
            var bestOcrIndex = -1
            var maxOverlap = 0f

            for (oIndex in ocrBlocks.indices) {
                if (matchedOcrIndices.contains(oIndex)) continue
                val ocrBlock = ocrBlocks[oIndex]
                val overlap = computeSpatialIntersectionRatio(ocrBlock.rawRect, aNode.bounds)
                if (overlap >= OVERLAP_THRESHOLD && overlap > maxOverlap) {
                    maxOverlap = overlap
                    bestOcrIndex = oIndex
                }
            }

            if (bestOcrIndex != -1) {
                val matchedOcr = ocrBlocks[bestOcrIndex]
                matchedOcrIndices.add(bestOcrIndex)
                matchedA11yIndices.add(aIndex)

                // Combine OCR precise text with accessibility interaction flags
                val combinedText = if (matchedOcr.text.isNotBlank()) matchedOcr.text else aNode.text
                val isClickable = aNode.isClickable || matchedOcr.elementType == SpatialVisionEngine.UIElementType.BUTTON
                val isEditable = aNode.isEditable || matchedOcr.elementType == SpatialVisionEngine.UIElementType.INPUT_FIELD

                fusedElements.add(
                    SemanticElement(
                        id = aNode.id,
                        text = combinedText,
                        bounds = if (!matchedOcr.rawRect.isEmpty) matchedOcr.rawRect else aNode.bounds,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        isScrollable = aNode.isScrollable,
                        className = aNode.className,
                        nodeSource = "FUSED"
                    )
                )
            }
        }

        // 2. Add remaining unmatched accessibility nodes
        for (aIndex in accessibilityNodes.indices) {
            if (!matchedA11yIndices.contains(aIndex)) {
                fusedElements.add(accessibilityNodes[aIndex])
            }
        }

        // 3. Add remaining unmatched OCR blocks
        for (oIndex in ocrBlocks.indices) {
            if (!matchedOcrIndices.contains(oIndex)) {
                val ocrBlock = ocrBlocks[oIndex]
                fusedElements.add(
                    SemanticElement(
                        id = ocrBlock.id,
                        text = ocrBlock.text,
                        bounds = ocrBlock.rawRect,
                        isClickable = ocrBlock.elementType == SpatialVisionEngine.UIElementType.BUTTON,
                        isEditable = ocrBlock.elementType == SpatialVisionEngine.UIElementType.INPUT_FIELD,
                        isScrollable = false,
                        className = if (ocrBlock.elementType == SpatialVisionEngine.UIElementType.BUTTON) "android.widget.Button" else "android.widget.TextView",
                        nodeSource = "OCR"
                    )
                )
            }
        }

        Log.d(
            TAG,
            "Semantic fusion completed: ${fusedElements.size} elements (${matchedA11yIndices.size} fused, " +
                    "${accessibilityNodes.size - matchedA11yIndices.size} a11y, ${ocrBlocks.size - matchedOcrIndices.size} ocr)"
        )

        return fusedElements
    }

    /**
     * Computes the geometric intersection ratio between two bounding rectangles
     * relative to the minimum rectangle area.
     */
    fun computeSpatialIntersectionRatio(rectA: Rect, rectB: Rect): Float {
        val intersectLeft = maxOf(rectA.left, rectB.left)
        val intersectTop = maxOf(rectA.top, rectB.top)
        val intersectRight = minOf(rectA.right, rectB.right)
        val intersectBottom = minOf(rectA.bottom, rectB.bottom)

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
            return 0f
        }

        val intersectArea = (intersectRight - intersectLeft).toFloat() * (intersectBottom - intersectTop).toFloat()
        val areaA = (rectA.width() * rectA.height()).toFloat().coerceAtLeast(1f)
        val areaB = (rectB.width() * rectB.height()).toFloat().coerceAtLeast(1f)
        val minArea = minOf(areaA, areaB)

        return (intersectArea / minArea).coerceIn(0f, 1f)
    }

    /**
     * Formats a list of [SemanticElement] objects into a JSON array for WebSocket dispatch.
     */
    fun toJsonArray(elements: List<SemanticElement>): JSONArray {
        val array = JSONArray()
        for (el in elements) {
            array.put(el.toJson())
        }
        return array
    }
}
