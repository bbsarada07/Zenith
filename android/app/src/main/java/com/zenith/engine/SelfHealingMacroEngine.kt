package com.zenith.engine

import android.graphics.Bitmap
import android.util.Log

/**
 * SelfHealingMacroEngine: Autonomous Spatial Automation with Computer Vision Self-Healing.
 *
 * Intercepts macro actions with target semantic text labels. Before injecting touches,
 * it inspects live screen frames with [SpatialVisionEngine] to detect UI element drift
 * and recalculates dynamic touch coordinates automatically.
 */
class SelfHealingMacroEngine(
    private val visionEngine: SpatialVisionEngine = SpatialVisionEngine()
) {

    companion object {
        private const val TAG = "SelfHealingMacroEngine"

        const val RESULT_SELF_HEALED = "SELF_HEALED_SUCCESS"
        const val RESULT_FALLBACK = "FALLBACK_COORDINATES"
    }

    data class MacroAction(
        val targetText: String,
        val fallbackXRatio: Float,
        val fallbackYRatio: Float
    )

    /**
     * Executes a self-healing macro action against the current screen bitmap.
     *
     * 1. Runs ML Kit OCR via [SpatialVisionEngine.processFrame].
     * 2. Searches OCR blocks for substring match on [action.targetText] (case-insensitive).
     * 3. IF MATCH FOUND:
     *    - targetX = block.centroid.x * screenWidth
     *    - targetY = block.centroid.y * screenHeight
     *    - Dispatches tap via [ZenithAccessibilityService.instance?.dispatchTap].
     *    - Returns "SELF_HEALED_SUCCESS".
     * 4. IF NO MATCH:
     *    - targetX = action.fallbackXRatio * screenWidth
     *    - targetY = action.fallbackYRatio * screenHeight
     *    - Dispatches tap via [ZenithAccessibilityService.instance?.dispatchTap].
     *    - Returns "FALLBACK_COORDINATES".
     */
    suspend fun executeAction(
        action: MacroAction,
        currentBitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): String {
        val (recognizedBlocks, _) = visionEngine.processFrame(currentBitmap)

        // Search for matching block (case-insensitive)
        val matchedBlock = recognizedBlocks.firstOrNull { block ->
            block.text.contains(action.targetText, ignoreCase = true)
        }

        return if (matchedBlock != null) {
            val targetX = matchedBlock.centroid.x * screenWidth
            val targetY = matchedBlock.centroid.y * screenHeight

            Log.i(
                TAG,
                "Self-healing match on '${action.targetText}' (Found: '${matchedBlock.text}') -> Tapping ($targetX, $targetY)"
            )

            ZenithAccessibilityService.instance?.dispatchTap(targetX, targetY)
            RESULT_SELF_HEALED
        } else {
            val targetX = action.fallbackXRatio * screenWidth
            val targetY = action.fallbackYRatio * screenHeight

            Log.w(
                TAG,
                "Target '${action.targetText}' not found in OCR frame. Falling back to coordinates ($targetX, $targetY)"
            )

            ZenithAccessibilityService.instance?.dispatchTap(targetX, targetY)
            RESULT_FALLBACK
        }
    }
}
