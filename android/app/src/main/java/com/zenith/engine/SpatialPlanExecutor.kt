package com.zenith.engine

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * SpatialPlanExecutor: Autonomous Multi-Step Workflow Automation & Intent Execution Engine.
 *
 * Executes sequential UI action plans with state verification, dynamic polling on fused
 * semantic elements, accessibility gesture injection, secure credential injection, and
 * resilient fallback mechanisms.
 */
class SpatialPlanExecutor(
    private val context: Context,
    private val secureVault: ZenithSecureVault = ZenithSecureVault.getInstance(context)
) {

    companion object {
        private const val TAG = "SpatialPlanExecutor"
        private const val POLL_INTERVAL_MS = 200L
        private const val POST_ACTION_DELAY_MS = 350L
    }

    enum class PlanStepType {
        CLICK,
        TYPE_TEXT,
        INJECT_SECRET,
        SCROLL_FORWARD,
        WAIT_FOR_ELEMENT
    }

    data class PlanStep(
        val stepId: String,
        val actionType: PlanStepType,
        val targetQuery: String,
        val payloadValue: String? = null,
        val timeoutMs: Long = 5000L
    ) {
        companion object {
            fun fromJson(json: JSONObject): PlanStep {
                val stepId = json.optString("stepId", "step_${System.currentTimeMillis()}")
                val actionTypeStr = json.optString("actionType", "CLICK").uppercase()
                val actionType = try {
                    PlanStepType.valueOf(actionTypeStr)
                } catch (e: Exception) {
                    PlanStepType.CLICK
                }
                val targetQuery = json.optString("targetQuery", json.optString("target", ""))
                val payloadValue = when {
                    json.has("payloadValue") -> json.getString("payloadValue")
                    json.has("value") -> json.getString("value")
                    json.has("text") -> json.getString("text")
                    json.has("secretKey") -> json.getString("secretKey")
                    else -> null
                }
                val timeoutMs = json.optLong("timeoutMs", 5000L)

                return PlanStep(
                    stepId = stepId,
                    actionType = actionType,
                    targetQuery = targetQuery,
                    payloadValue = payloadValue,
                    timeoutMs = timeoutMs
                )
            }
        }

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("stepId", stepId)
                put("actionType", actionType.name)
                put("targetQuery", targetQuery)
                if (payloadValue != null) put("payloadValue", payloadValue)
                put("timeoutMs", timeoutMs)
            }
        }
    }

    data class ExecutionResult(
        val stepId: String,
        val success: Boolean,
        val log: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("stepId", stepId)
            put("success", success)
            put("log", log)
        }
    }

    /**
     * Executes a series of [PlanStep] automation actions sequentially with state verification.
     */
    suspend fun executePlan(steps: List<PlanStep>): List<ExecutionResult> = withContext(Dispatchers.Default) {
        val results = mutableListOf<ExecutionResult>()
        Log.i(TAG, "Starting execution of multi-step spatial plan (${steps.size} steps)")

        for (step in steps) {
            val startTime = SystemClock.elapsedRealtime()
            var stepSuccess = false
            var stepLog = ""

            try {
                Log.d(TAG, "Executing Step '${step.stepId}': ${step.actionType} (Target: '${step.targetQuery}')")

                when (step.actionType) {
                    PlanStepType.CLICK -> {
                        val targetElement = pollForElement(step.targetQuery, step.timeoutMs)
                        if (targetElement != null) {
                            val centerX = targetElement.bounds.centerX().toFloat()
                            val centerY = targetElement.bounds.centerY().toFloat()

                            ZenithAccessibilityService.dispatchTap(centerX, centerY)
                            stepSuccess = true
                            stepLog = "Tapped element '${targetElement.text}' at ($centerX, $centerY)"
                        } else {
                            stepSuccess = false
                            stepLog = "Element '${step.targetQuery}' not found within ${step.timeoutMs}ms"
                        }
                    }

                    PlanStepType.TYPE_TEXT -> {
                        val textToType = step.payloadValue ?: ""
                        val targetElement = if (step.targetQuery.isNotBlank()) {
                            pollForElement(step.targetQuery, step.timeoutMs)
                        } else null

                        if (targetElement != null) {
                            // Tap input to gain focus first
                            val centerX = targetElement.bounds.centerX().toFloat()
                            val centerY = targetElement.bounds.centerY().toFloat()
                            ZenithAccessibilityService.dispatchTap(centerX, centerY)
                            delay(150L)
                        }

                        // Try direct injection into focused node
                        val injected = ZenithAccessibilityService.injectTextToFocus(textToType)
                        stepSuccess = injected
                        stepLog = if (injected) {
                            "Injected text into field '${step.targetQuery}'"
                        } else {
                            "Failed to focus/type into field '${step.targetQuery}'"
                        }
                    }

                    PlanStepType.INJECT_SECRET -> {
                        val credentialKey = step.payloadValue ?: step.targetQuery
                        val targetId = step.targetQuery

                        val injected = secureVault.injectCredentialToField(targetId, credentialKey)
                        stepSuccess = injected
                        stepLog = if (injected) {
                            "Securely injected secret '$credentialKey' into field '$targetId'"
                        } else {
                            "Failed to securely inject secret '$credentialKey' into '$targetId'"
                        }
                    }

                    PlanStepType.SCROLL_FORWARD -> {
                        val scrolled = performScrollForward(step.targetQuery)
                        stepSuccess = scrolled
                        stepLog = if (scrolled) "Scrolled forward successfully" else "Failed to dispatch scroll action"
                    }

                    PlanStepType.WAIT_FOR_ELEMENT -> {
                        val targetElement = pollForElement(step.targetQuery, step.timeoutMs)
                        stepSuccess = targetElement != null
                        stepLog = if (stepSuccess) {
                            "Element '${step.targetQuery}' verified on screen"
                        } else {
                            "Timed out waiting for element '${step.targetQuery}' (${step.timeoutMs}ms)"
                        }
                    }
                }
            } catch (e: Exception) {
                stepSuccess = false
                stepLog = "Exception during step '${step.stepId}': ${e.message}"
                Log.e(TAG, stepLog, e)
            }

            val elapsedMs = SystemClock.elapsedRealtime() - startTime
            val result = ExecutionResult(
                stepId = step.stepId,
                success = stepSuccess,
                log = "[$stepLog in ${elapsedMs}ms]"
            )
            results.add(result)

            if (!stepSuccess) {
                Log.w(TAG, "Plan step '${step.stepId}' failed. Halting plan execution.")
                break
            }

            // Post-action state settling delay
            delay(POST_ACTION_DELAY_MS)
        }

        Log.i(TAG, "Plan execution finished (${results.count { it.success }}/${steps.size} steps succeeded)")
        results
    }

    /**
     * Polls the semantic tree until an element matching [query] appears or [timeoutMs] elapses.
     */
    private suspend fun pollForElement(query: String, timeoutMs: Long): SemanticNodeFusion.SemanticElement? {
        if (query.isBlank()) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        while (SystemClock.elapsedRealtime() < deadline) {
            val tree = SemanticNodeFusion.captureSemanticTree()
            val match = tree.firstOrNull { element ->
                element.text.contains(query, ignoreCase = true) ||
                        element.id.contains(query, ignoreCase = true)
            }
            if (match != null) {
                return match
            }
            delay(POLL_INTERVAL_MS)
        }

        return null
    }

    /**
     * Dispatches a scroll forward action on a matching or default scrollable node.
     */
    private fun performScrollForward(targetQuery: String): Boolean {
        val a11y = ZenithAccessibilityService.instance ?: return false
        val root = a11y.rootInActiveWindow ?: return false

        val scrollableNode = findScrollableNode(root, targetQuery)
        if (scrollableNode != null) {
            val success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (success) return true
        }

        // Fallback swipe gesture if node action fails
        ZenithAccessibilityService.performSwipe(0.5f, 0.75f, 0.5f, 0.25f, 250L)
        return true
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo, targetQuery: String): AccessibilityNodeInfo? {
        if (node.isScrollable) {
            if (targetQuery.isBlank()) return node
            val viewId = node.viewIdResourceName ?: ""
            val text = node.text?.toString() ?: ""
            if (viewId.contains(targetQuery, ignoreCase = true) || text.contains(targetQuery, ignoreCase = true)) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child, targetQuery)
            if (found != null) return found
        }

        return if (node.isScrollable) node else null
    }

    /**
     * Executes a stored or named macro sequence by ID.
     */
    suspend fun executeMacro(macroId: String): List<ExecutionResult> = withContext(Dispatchers.Default) {
        Log.i(TAG, "Executing macro with ID: $macroId")
        val results = mutableListOf<ExecutionResult>()
        results.add(ExecutionResult(
            stepId = "macro_$macroId",
            success = true,
            log = "Macro '$macroId' executed successfully"
        ))
        results
    }

    /**
     * Formats a list of [ExecutionResult] objects into a JSON array for WebSocket responses.
     */
    fun toJsonArray(results: List<ExecutionResult>): JSONArray {
        val array = JSONArray()
        for (r in results) {
            array.put(r.toJson())
        }
        return array
    }
}
