package com.billu.printer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BilluAccessibilityService : AccessibilityService() {

    companion object {
        var instance: BilluAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var isScrapingNow = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        evaluatePartnerAppVisibility(rootInActiveWindow?.packageName?.toString())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isScrapingNow) return
        // Only react to actual foreground window state changes so our own floating button never triggers a self-hide!
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            evaluatePartnerAppVisibility(pkg)
            // Re-verify after 350ms in case a Recents/Launcher transition animation was still finishing
            handler.postDelayed({
                if (!isScrapingNow) {
                    evaluatePartnerAppVisibility(rootInActiveWindow?.packageName?.toString())
                }
            }, 350L)
        }
    }

    private fun evaluatePartnerAppVisibility(pkg: String?) {
        if (pkg.isNullOrEmpty()) return
        val lower = pkg.lowercase()

        // Ignore transient overlays, keyboards, WebView sub-windows, and our own overlay window
        if (lower == "com.billu.printer" ||
            lower == "com.android.systemui" ||
            lower.contains("webview") ||
            lower.contains("inputmethod") ||
            lower.contains("honeyboard") ||
            lower.contains("gboard") ||
            lower.contains("keyboard") ||
            lower.contains("permissioncontroller")) {
            return
        }

        val prefs = getSharedPreferences("billu_native_prefs", Context.MODE_PRIVATE)
        val customPkg = prefs.getString("custom_partner_pkg", "") ?: ""

        val isPartnerApp = lower.contains("pandaportal") ||
                lower.contains("foodpanda") ||
                (customPkg.isNotEmpty() && lower == customPkg.lowercase())

        BilluBridgeService.instance?.updateFloatingBubbleVisibility(isPartnerApp)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        BilluBridgeService.instance?.updateFloatingBubbleVisibility(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Exact MacroDroid "Print Foodpanda" Sequence:
     * 1. Read Screen Contents -> fp_screen_1
     * 2. Repeat 3 times: Gesture (250ms: 50%,80% -> 50%,60%) + Wait 400ms
     * 3. Read Screen Contents -> fp_screen_2
     * 4. Repeat 3 times: Gesture (250ms: 50%,60% -> 50%,80%) + Wait 350ms
     * 5. Read Screen Contents -> fp_screen_3
     * 6. Write to File: FP_Log_YYYY_MM_DD_HH_MM_SS.txt
     */
    fun captureFoodpandaOrderMultiScroll(onComplete: (String?) -> Unit) {
        if (isScrapingNow) return
        isScrapingNow = true
        var completed = false

        fun finishOnce(resultB64: String?) {
            if (completed) return
            completed = true
            isScrapingNow = false
            onComplete(resultB64)
        }

        // 6-second safety watchdog so `isScrapingNow` can never get stuck if Android cancels a gesture
        handler.postDelayed({
            if (!completed) {
                val fallbackDump = dumpCurrentScreenNodes()
                val b64 = if (fallbackDump.length > 4) {
                    Base64.encodeToString(fallbackDump.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                } else null
                finishOnce(b64)
            }
        }, 6500L)

        // Step 1: Read Screen Contents -> fp_screen_1
        val fpScreen1 = dumpCurrentScreenNodes()

        // Step 2: Repeat 3 times: Gesture (250ms: 50%,80% -> 50%,60%)
        performRepeatedVerticalGesture(
            repeatCount = 3,
            startYRatio = 0.80f,
            endYRatio = 0.60f,
            durationMs = 250L,
            stepGapMs = 140L
        ) {
            // Wait 400 ms
            handler.postDelayed({
                // Step 3: Read Screen Contents -> fp_screen_2
                val fpScreen2 = dumpCurrentScreenNodes()

                // Step 4: Repeat 3 times: Gesture (250ms: 50%,60% -> 50%,80%)
                performRepeatedVerticalGesture(
                    repeatCount = 3,
                    startYRatio = 0.60f,
                    endYRatio = 0.80f,
                    durationMs = 250L,
                    stepGapMs = 140L
                ) {
                    // Wait 350 ms
                    handler.postDelayed({
                        // Step 5: Read Screen Contents -> fp_screen_3
                        val fpScreen3 = dumpCurrentScreenNodes()

                        val validScreens = listOf(fpScreen1, fpScreen2, fpScreen3).filter { it.length > 4 }
                        if (validScreens.isEmpty()) {
                            finishOnce(null)
                            return@postDelayed
                        }

                        val combinedRaw = validScreens.joinToString("\n===SCREEN_BREAK===\n")
                        saveRawFpLogFile(combinedRaw)

                        val b64 = Base64.encodeToString(combinedRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        finishOnce(b64)
                    }, 350L)
                }
            }, 400L)
        }
    }

    private fun dumpCurrentScreenNodes(): String {
        val jsonObj = JSONObject()
        var indexCounter = 0

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val txt = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val combinedVal = when {
                txt.isNotEmpty() && desc.isNotEmpty() && txt != desc -> "$txt\n$desc"
                txt.isNotEmpty() -> txt
                else -> desc
            }

            val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            if (combinedVal.isNotEmpty()) {
                if (viewId.startsWith("test_id_")) {
                    jsonObj.put(viewId, combinedVal)
                }
                jsonObj.put("index:$indexCounter", combinedVal)
                indexCounter++
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }

        try {
            val root = rootInActiveWindow
            if (root != null) {
                walk(root)
            }
            if (indexCounter == 0) {
                for (w in windows) {
                    val wPkg = w.root?.packageName?.toString()?.lowercase() ?: ""
                    if (wPkg != "com.billu.printer" && w.root != null) {
                        walk(w.root)
                    }
                }
            }
        } catch (_: Exception) {}

        return jsonObj.toString()
    }

    private fun performRepeatedVerticalGesture(
        repeatCount: Int,
        startYRatio: Float,
        endYRatio: Float,
        durationMs: Long,
        stepGapMs: Long,
        onFinishedAll: () -> Unit
    ) {
        if (repeatCount <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onFinishedAll()
            return
        }

        val dm = resources.displayMetrics
        val cx = dm.widthPixels * 0.50f
        val startY = dm.heightPixels * startYRatio
        val endY = dm.heightPixels * endYRatio

        fun runOne(remaining: Int) {
            if (remaining <= 0) {
                onFinishedAll()
                return
            }
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.postDelayed({ runOne(remaining - 1) }, stepGapMs)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.postDelayed({ runOne(remaining - 1) }, stepGapMs)
                }
            }, null)

            if (!ok) {
                handler.postDelayed({ runOne(remaining - 1) }, durationMs + stepGapMs)
            }
        }

        runOne(repeatCount)
    }

    private fun saveRawFpLogFile(rawContent: String) {
        try {
            val stamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
            val fileName = "FP_Log_$stamp.txt"
            val bytes = rawContent.toByteArray(Charsets.UTF_8)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Billu")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Billu")
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
            }
        } catch (_: Exception) {}
    }
}
