package com.focusfeed.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FocusAccessibilityService : AccessibilityService() {

    private var lastActionMs = 0L
    private val COOLDOWN_MS = 500L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (now - lastActionMs < COOLDOWN_MS) return

        when (pkg) {
            "com.instagram.android" -> handleInstagram(event, now)
            "com.google.android.youtube" -> handleYoutube(event, now)
        }
    }

    private fun handleInstagram(event: AccessibilityEvent, now: Long) {
        val root = rootInActiveWindow ?: return
        // Find and hide the Reels tab (bottom nav, 3rd item on modern Instagram)
        val acted = hideNodeByDescription(root, listOf("Reels", "Reels tab")) ||
            hideNodeByResourceId(root, listOf(
                "com.instagram.android:id/clips_tab",
                "com.instagram.android:id/reels_tab"
            ))
        if (acted) lastActionMs = now
        root.recycle()
    }

    private fun handleYoutube(event: AccessibilityEvent, now: Long) {
        val root = rootInActiveWindow ?: return
        val acted = hideNodeByDescription(root, listOf("Shorts", "YouTube Shorts")) ||
            hideNodeByResourceId(root, listOf(
                "com.google.android.youtube:id/shorts_pivot_bar_item",
                "com.google.android.youtube:id/reel_pivot_bar_item"
            ))
        if (acted) lastActionMs = now
        root.recycle()
    }

    private fun hideNodeByDescription(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        var found = false
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                // Click the node's parent container to navigate away if we landed on Reels/Shorts
                val parent = node.parent
                if (parent != null) {
                    // We can't truly "hide" native views; instead navigate back
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    parent.recycle()
                    found = true
                    break
                }
                node.recycle()
            }
            if (found) break
        }
        return found
    }

    private fun hideNodeByResourceId(root: AccessibilityNodeInfo, ids: List<String>): Boolean {
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                // If a Reels/Shorts tab is focused/selected, go back to home
                val selected = nodes.any { it.isSelected || it.isFocused }
                nodes.forEach { it.recycle() }
                if (selected) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return true
                }
            }
        }
        return false
    }

    override fun onInterrupt() {}
}
