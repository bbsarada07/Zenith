package com.zenith.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * MacroEngine: Production Spatial Macro Recorder & Persistent Autonomous Execution Engine.
 *
 * Capabilities:
 * 1. Sequences user touch/swipe inputs with accurate inter-step timestamp delays.
 * 2. Persistent Storage: Saves and loads named automation recipes to `/data/user/0/com.zenith/files/macros/`.
 * 3. Self-Healing Execution: Uses local OCR to dynamically relocate UI elements before dispatching gestures.
 * 4. WebSocket Management API: Exposes START_RECORD, STOP_RECORD, GET_MACROS, SAVE_MACRO, DELETE_MACRO, EXECUTE_MACRO.
 */
class MacroEngine(
    private val context: Context? = null,
    private val frameProvider: () -> Bitmap? = { null }
) {

    companion object {
        private const val TAG = "ZenithMacroEngine"
    }

    data class MacroStep(
        val stepNumber: Int,
        val type: String, // "tap", "swipe", "text", "key"
        val x: Float = 0f,
        val y: Float = 0f,
        val startX: Float = 0f,
        val startY: Float = 0f,
        val endX: Float = 0f,
        val endY: Float = 0f,
        val durationMs: Long = 250L,
        val delayMs: Long = 500L,
        val label: String? = null,
        val payload: String? = null
    )

    data class MacroRecipe(
        val id: String,
        val name: String,
        val createdAt: Long,
        val steps: List<MacroStep>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("createdAt", createdAt)
            put("stepCount", steps.size)
            val stepsArray = JSONArray()
            for (step in steps) {
                val stepObj = JSONObject().apply {
                    put("step", step.stepNumber)
                    put("type", step.type)
                    put("delay_ms", step.delayMs)
                    if (step.label != null) put("label", step.label)
                    if (step.payload != null) put("payload", step.payload)

                    when (step.type) {
                        "tap" -> {
                            put("x", step.x.toDouble())
                            put("y", step.y.toDouble())
                        }
                        "swipe" -> {
                            put("startX", step.startX.toDouble())
                            put("startY", step.startY.toDouble())
                            put("endX", step.endX.toDouble())
                            put("endY", step.endY.toDouble())
                            put("durationMs", step.durationMs)
                        }
                    }
                }
                stepsArray.put(stepObj)
            }
            put("steps", stepsArray)
        }
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recordedSteps = CopyOnWriteArrayList<MacroStep>()
    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var isPlaying: Boolean = false
        private set

    private var lastActionTimestampMs: Long = 0L

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val macrosDir: File? by lazy {
        context?.let { ctx ->
            File(ctx.filesDir, "macros").apply {
                if (!exists()) mkdirs()
            }
        }
    }

    fun startRecording() {
        recordedSteps.clear()
        lastActionTimestampMs = SystemClock.elapsedRealtime()
        isRecording = true
        Log.i(TAG, "Macro recording started.")
    }

    fun recordTap(x: Float, y: Float, label: String? = null) {
        if (!isRecording) return
        val now = SystemClock.elapsedRealtime()
        val delay = (now - lastActionTimestampMs).coerceIn(100L, 5000L)
        lastActionTimestampMs = now

        val step = MacroStep(
            stepNumber = recordedSteps.size + 1,
            type = "tap",
            x = x,
            y = y,
            delayMs = delay,
            label = label
        )
        recordedSteps.add(step)
        Log.d(TAG, "Recorded step #${step.stepNumber}: TAP ($x, $y) after ${delay}ms [label=$label]")
    }

    fun recordSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 250L) {
        if (!isRecording) return
        val now = SystemClock.elapsedRealtime()
        val delay = (now - lastActionTimestampMs).coerceIn(100L, 5000L)
        lastActionTimestampMs = now

        val step = MacroStep(
            stepNumber = recordedSteps.size + 1,
            type = "swipe",
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            delayMs = delay
        )
        recordedSteps.add(step)
        Log.d(TAG, "Recorded step #${step.stepNumber}: SWIPE ($startX,$startY)->($endX,$endY) after ${delay}ms")
    }

    fun stopRecording(saveName: String? = null): JSONObject {
        isRecording = false
        val macroId = UUID.randomUUID().toString().take(8)
        val name = saveName ?: "Macro_$macroId"
        val recipe = MacroRecipe(
            id = macroId,
            name = name,
            createdAt = System.currentTimeMillis(),
            steps = recordedSteps.toList()
        )

        // Persist to internal storage
        saveMacroRecipe(recipe)
        Log.i(TAG, "Macro recording stopped and saved: '$name' (${recordedSteps.size} steps)")
        return recipe.toJson()
    }

    // --- Persistence Management ---

    fun saveMacroRecipe(recipe: MacroRecipe): Boolean {
        val dir = macrosDir ?: return false
        return try {
            val file = File(dir, "${recipe.id}.json")
            file.writeText(recipe.toJson().toString(2))
            Log.i(TAG, "Saved macro to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save macro recipe ${recipe.id}: ${e.message}", e)
            false
        }
    }

    fun loadMacroRecipe(id: String): MacroRecipe? {
        val dir = macrosDir ?: return null
        return try {
            val file = File(dir, "$id.json")
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            parseRecipeFromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load macro $id: ${e.message}", e)
            null
        }
    }

    fun listSavedMacros(): List<JSONObject> {
        val dir = macrosDir ?: return emptyList()
        val list = mutableListOf<JSONObject>()
        try {
            val files = dir.listFiles { f -> f.extension == "json" } ?: emptyArray()
            for (f in files) {
                try {
                    val json = JSONObject(f.readText())
                    list.add(JSONObject().apply {
                        put("id", json.optString("id", f.nameWithoutExtension))
                        put("name", json.optString("name", "Unnamed Macro"))
                        put("createdAt", json.optLong("createdAt", f.lastModified()))
                        put("stepCount", json.optInt("stepCount", 0))
                    })
                } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing macros: ${e.message}", e)
        }
        return list
    }

    fun deleteMacro(id: String): Boolean {
        val dir = macrosDir ?: return false
        val file = File(dir, "$id.json")
        return file.delete()
    }

    fun parseRecipeFromJson(json: JSONObject): MacroRecipe {
        val id = json.optString("id", UUID.randomUUID().toString().take(8))
        val name = json.optString("name", "Macro_$id")
        val createdAt = json.optLong("createdAt", System.currentTimeMillis())
        val stepsArray = json.optJSONArray("steps") ?: JSONArray()
        val steps = parseStepsFromJsonArray(stepsArray)
        return MacroRecipe(id, name, createdAt, steps)
    }

    fun parseStepsFromJsonArray(array: JSONArray): List<MacroStep> {
        val list = mutableListOf<MacroStep>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val step = MacroStep(
                stepNumber = obj.optInt("step", i + 1),
                type = obj.optString("type", "tap"),
                x = obj.optDouble("x", 0.5).toFloat(),
                y = obj.optDouble("y", 0.5).toFloat(),
                startX = obj.optDouble("startX", 0.5).toFloat(),
                startY = obj.optDouble("startY", 0.8).toFloat(),
                endX = obj.optDouble("endX", 0.5).toFloat(),
                endY = obj.optDouble("endY", 0.2).toFloat(),
                durationMs = obj.optLong("durationMs", 250L),
                delayMs = obj.optLong("delay_ms", 500L),
                label = if (obj.has("label")) obj.getString("label") else null,
                payload = if (obj.has("payload")) obj.getString("payload") else null
            )
            list.add(step)
        }
        return list
    }

    // --- Execution & Self-Healing Playback ---

    fun playMacro(
        macroJson: String? = null,
        macroId: String? = null,
        enableSelfHealing: Boolean = true,
        onStepProgress: ((stepNum: Int, total: Int, desc: String) -> Unit)? = null
    ) {
        val steps: List<MacroStep> = when {
            !macroId.isNullOrBlank() -> loadMacroRecipe(macroId)?.steps ?: emptyList()
            !macroJson.isNullOrBlank() -> {
                try {
                    if (macroJson.trim().startsWith("{")) {
                        parseRecipeFromJson(JSONObject(macroJson)).steps
                    } else {
                        parseStepsFromJsonArray(JSONArray(macroJson))
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            else -> recordedSteps.toList()
        }

        if (steps.isEmpty()) {
            Log.w(TAG, "Cannot play macro: step list is empty.")
            return
        }

        if (isPlaying) {
            Log.w(TAG, "Macro playback already in progress.")
            return
        }

        isPlaying = true

        engineScope.launch {
            try {
                Log.i(TAG, "Starting playback of ${steps.size} macro steps...")
                for ((index, step) in steps.withIndex()) {
                    if (!isPlaying) break

                    delay(step.delayMs.coerceAtLeast(150L))

                    var targetX = step.x
                    var targetY = step.y

                    // Self-Healing Coordinate Check: If label is present, verify on-screen
                    if (enableSelfHealing && !step.label.isNullOrBlank()) {
                        val healedCoords = locateElementByOcr(step.label)
                        if (healedCoords != null) {
                            Log.i(TAG, "Self-Healing: Relocated '${step.label}' from ($targetX, $targetY) to (${healedCoords.first}, ${healedCoords.second})")
                            targetX = healedCoords.first
                            targetY = healedCoords.second
                        }
                    }

                    val desc = when (step.type) {
                        "tap" -> {
                            ZenithAccessibilityService.performClickAt(targetX, targetY)
                            "Tapped at (${(targetX * 100).toInt()}%, ${(targetY * 100).toInt()}%)"
                        }
                        "swipe" -> {
                            ZenithAccessibilityService.performSwipe(
                                step.startX,
                                step.startY,
                                step.endX,
                                step.endY,
                                step.durationMs
                            )
                            "Swiped (${(step.startX * 100).toInt()}%) -> (${(step.endX * 100).toInt()}%)"
                        }
                        "text" -> {
                            val text = step.payload ?: ""
                            ZenithAccessibilityService.injectTextToFocus(text)
                            "Injected text '$text'"
                        }
                        else -> "Executed step ${step.stepNumber}"
                    }

                    onStepProgress?.invoke(index + 1, steps.size, desc)
                }
                Log.i(TAG, "Macro playback completed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during macro playback: ${e.message}", e)
            } finally {
                isPlaying = false
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        Log.i(TAG, "Macro playback stopped.")
    }

    /**
     * Uses ML Kit OCR to locate a text label on the active screen and return normalized center coordinates (x, y).
     */
    private suspend fun locateElementByOcr(targetLabel: String): Pair<Float, Float>? {
        val bitmap = frameProvider() ?: return null

        return suspendCancellableCoroutine { continuation ->
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        for (block in visionText.textBlocks) {
                            if (block.text.contains(targetLabel, ignoreCase = true)) {
                                val rect = block.boundingBox
                                if (rect != null) {
                                    val normX = (rect.centerX().toFloat() / bitmap.width.toFloat()).coerceIn(0f, 1f)
                                    val normY = (rect.centerY().toFloat() / bitmap.height.toFloat()).coerceIn(0f, 1f)
                                    continuation.resume(Pair(normX, normY))
                                    return@addOnSuccessListener
                                }
                            }
                        }
                        continuation.resume(null)
                    }
                    .addOnFailureListener {
                        continuation.resume(null)
                    }
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }
    }
}
