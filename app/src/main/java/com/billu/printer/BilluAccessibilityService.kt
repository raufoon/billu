package com.billu.printer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class BilluAccessibilityService : AccessibilityService() {

    companion object {
        var instance: BilluAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore system UI overlays (volume/notification shade)
            if (pkg == "com.android.systemui" || pkg == "com.billu.printer") return
            val isFoodpanda = pkg.contains("pandaportal") || pkg.contains("foodpanda")
            // Keep bubble visible over Foodpanda (or always if user prefers)
            BilluBridgeService.instance?.updateFloatingBubbleVisibility(isFoodpanda || true)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Captures the Foodpanda screen tree. If the order is long and "Total" / "Payment method"
     * is below the fold, automatically scrolls down up to 3 times (`===SCREEN_BREAK===`)
     * so 100% of items and the final total are captured!
     */
    fun captureFoodpandaOrderMultiScroll(onComplete: (String?) -> Unit) {
        val screens = mutableListOf<String>()
        var scrollCount = 0
        val maxScrolls = 3

        fun captureStep() {
            val root = rootInActiveWindow
            if (root == null) {
                if (screens.isNotEmpty()) {
                    val combined = screens.joinToString("\n===SCREEN_BREAK===\n")
                    val b64 = Base64.encodeToString(combined.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    onComplete(b64)
                } else {
                    onComplete(null)
                }
                return
            }

            val jsonObj = JSONObject()
            var indexCounter = 0
            val fullScreenText = StringBuilder()

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
                    fullScreenText.append(combinedVal).append("\n")
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

            walk(root)
            val dumpStr = jsonObj.toString()
            if (dumpStr.length > 4) {
                screens.add(dumpStr)
            }

            val flat = fullScreenText.toString()
            val hasReachedBottom = Regex("(?i)(Payment method|Delivery type|Paid with|Cash On Delivery|\\bTotal\\b)").containsMatchIn(flat)

            if (!hasReachedBottom && scrollCount < maxScrolls) {
                scrollCount++
                val scrolled = performScrollDown(root)
                if (scrolled) {
                    handler.postDelayed({ captureStep() }, 420)
                    return
                }
            }

            val combined = screens.joinToString("\n===SCREEN_BREAK===\n")
            val b64 = Base64.encodeToString(combined.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            onComplete(b64)
        }

        captureStep()
    }

    private fun performScrollDown(root: AccessibilityNodeInfo): Boolean {
        fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id }) {
                return node
            }
            for (i in 0 until node.childCount) {
                val found = findScrollable(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        val scrollableNode = findScrollable(root)
        if (scrollableNode != null) {
            val ok = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (ok) return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dm = resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val startY = dm.heightPixels * 0.75f
            val endY = dm.heightPixels * 0.28f
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 220)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }
}
