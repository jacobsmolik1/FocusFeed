package com.focusfeed.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FocusAccessibilityService : AccessibilityService() {

    private var lastInterventionMs = 0L
    private val COOLDOWN_MS = 800L

    // Reels-related view ID fragments (Instagram)
    private val igReelIds = listOf("reel_viewer", "clips_video_container", "clips_tab", "reels_tab")

    // Shorts-related view ID fragments (YouTube)
    private val ytShortsIds = listOf("shorts_container", "reel_recycler", "shorts_pivot_bar_item")

    // Content-description labels to intercept (checked case-insensitively)
    private val igReelLabels = listOf("reels", "reels tab")
    private val ytShortsLabels = listOf("shorts", "youtube shorts")

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!FocusPrefs.isAllowStories(this)) {
            // If strict blocking is desired we also check stories — currently no-op since
            // isAllowStories() defaults true; kept as extension point.
        }

        val pkg = event.packageName?.toString() ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastInterventionMs < COOLDOWN_MS) return

        when (pkg) {
            "com.instagram.android" -> checkInstagram(now)
            "com.google.android.youtube" -> checkYoutube(now)
        }
    }

    private fun checkInstagram(now: Long) {
        val root = rootInActiveWindow ?: return
        val blocked =
            containsNodeById(root, igReelIds) ||
            containsNodeByLabel(root, igReelLabels)
        root.recycle()
        if (blocked) intervene(now, "instagram")
    }

    private fun checkYoutube(now: Long) {
        val root = rootInActiveWindow ?: return
        val blocked =
            containsNodeById(root, ytShortsIds) ||
            containsNodeByLabel(root, ytShortsLabels)
        root.recycle()
        if (blocked) intervene(now, "youtube")
    }

    private fun intervene(now: Long, platform: String) {
        lastInterventionMs = now
        FocusStats.recordBlock(this)
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // Recursive DFS: check if any node's viewIdResourceName contains one of the fragments
    private fun containsNodeById(node: AccessibilityNodeInfo, fragments: List<String>): Boolean {
        val id = node.viewIdResourceName ?: ""
        if (fragments.any { id.contains(it, ignoreCase = true) && nodeIsVisible(node) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsNodeById(child, fragments)) { child.recycle(); return true }
            child.recycle()
        }
        return false
    }

    // Recursive DFS: check content-description labels
    private fun containsNodeByLabel(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        if (desc.isNotEmpty() && labels.any { desc == it || desc.startsWith("$it ") } && nodeIsVisible(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsNodeByLabel(child, labels)) { child.recycle(); return true }
            child.recycle()
        }
        return false
    }

    // Only trigger on nodes that are actually on-screen
    private fun nodeIsVisible(node: AccessibilityNodeInfo): Boolean =
        node.isVisibleToUser

    override fun onInterrupt() {}
}
