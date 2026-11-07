package com.billu.printer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
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
    @Volatile private var isScrapingNow = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        checkActiveWindowAndSyncBubble(rootInActiveWindow?.packageName?.toString())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isScrapingNow) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val pkg = event.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() ?: return
            checkActiveWindowAndSyncBubble(pkg)
        }
    }

    private fun checkActiveWindowAndSyncBubble(pkg: String?) {
        if (pkg.isNullOrEmpty()) return
        // Ignore transient volume/notification shade or keyboard overlays so they don't flicker the button
        if (pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg.contains("honeyboard") ||
            pkg.contains("keyboard")) {
            return
        }

        val prefs = getSharedPreferences("billu_native_prefs", Context.MODE_PRIVATE)
        val customPkg = prefs.getString("custom_partner_pkg", "") ?: ""

        val isPartnerApp = pkg.contains("pandaportal", ignoreCase = true) ||
                pkg.contains("foodpanda", ignoreCase = true) ||
                (customPkg.isNotEmpty() && pkg.equals(customPkg, ignoreCase = true))

        BilluBridgeService.instance?.updateFloatingBubbleVisibility(isPartnerApp)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        BilluBridgeService.instance?.updateFloatingBubbleVisibility(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Multi-scroll screen scraper:
     * 1. Reads all nodes on the current screen.
     * 2. Swipes up to scroll down repeatedly (up to 5 scrolls) until either the true bottom footer
     *    ("Delivery type" / "Need help" / "Contact help") is visible OR two consecutive screens are identical.
     */
    fun captureFoodpandaOrderMultiScroll(onComplete: (String?) -> Unit) {
        if (isScrapingNow) return
        isScrapingNow = true

        val screens = mutableListOf<String>()
        var lastSignature = ""
        var scrollCount = 0
        val maxScrolls = 5

        fun finishScrape() {
            isScrapingNow = false
            if (screens.isEmpty()) {
                onComplete(null)
            } else {
                val combined = screens.joinToString("\n===SCREEN_BREAK===\n")
                val b64 = Base64.encodeToString(combined.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                onComplete(b64)
            }
        }

        fun captureStep() {
            val root = rootInActiveWindow
            if (root == null) {
                finishScrape()
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

            try {
                walk(root)
            } catch (_: Exception) {}

            val dumpStr = jsonObj.toString()
            val currentSig = fullScreenText.toString().trim()

            if (dumpStr.length > 4 && currentSig != lastSignature) {
                screens.add(dumpStr)
            }

            // Stop if the scroll view didn't move at all (we physically hit the bottom)
            val hitPhysicalBottom = (scrollCount > 0 && currentSig == lastSignature)
            lastSignature = currentSig

            // Only treat the absolute bottom footer labels as bottom-of-receipt (NOT "Total", which can be sticky!)
            val hasTrueBottomFooter = Regex("(?i)(Delivery type|Need help with this order|Contact help centre)").containsMatchIn(currentSig)

            if (!hitPhysicalBottom && !hasTrueBottomFooter && scrollCount < maxScrolls) {
                scrollCount++
                performSwipeUpScroll(root) {
                    handler.postDelayed({ captureStep() }, 480)
                }
            } else {
                finishScrape()
            }
        }

        captureStep()
    }

    private fun performSwipeUpScroll(root: AccessibilityNodeInfo, onScrolled: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dm = resources.displayMetrics
            val cx = dm.widthPixels * 0.5f
            val startY = dm.heightPixels * 0.76f
            val endY = dm.heightPixels * 0.24f
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 260)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onScrolled()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    fallbackNodeScroll(root)
                    onScrolled()
                }
            }, null)
            if (dispatched) return
        }

        fallbackNodeScroll(root)
        onScrolled()
    }

    private fun fallbackNodeScroll(root: AccessibilityNodeInfo): Boolean {
        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = 0

        fun findLargestScrollable(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id }) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()
                if (area > bestArea) {
                    bestArea = area
                    bestNode = node
                }
            }
            for (i in 0 until node.childCount) {
                findLargestScrollable(node.getChild(i))
            }
        }

        findLargestScrollable(root)
        return bestNode?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id) ?: false
    }
}
