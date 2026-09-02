package com.example

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * File Location: app/src/main/java/com/example/AutoKickAccessibilityService.kt
 *
 * Highly robust AccessibilityService that auto-kicks the user out of short-form video
 * players (YouTube Shorts, Instagram Reels, TikTok) while strictly PREVENTING false positives
 * on the YouTube / Instagram home screens, feed tabs, and shelves.
 */
class AutoKickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoKickService"

        private val TARGET_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android"
        )

        // Substrings for explicit Short-Form video player action panels and exclusive controls
        private val PLAYER_ACTION_KEYWORDS = listOf(
            "dislike this short",
            "remix this video",
            "sound used in this short",
            "shorts video player",
            "audio used in this reel",
            "original audio",
            "remix reel",
            "use template",
            "use audio",
            "reels camera"
        )

        // View IDs explicitly tied to active full-screen short video player containers
        private val PLAYER_CONTAINER_VIEW_IDS = listOf(
            "reel_player",
            "reel_watch",
            "shorts_player",
            "shorts_container",
            "reel_recycler_view",
            "reel_video_view",
            "shorts_action_panel",
            "reel_player_page_tree",
            "clips_viewer",
            "reel_viewer",
            "clips_video_container",
            "clips_media_item"
        )

        // Cooldown period in milliseconds between kicks to prevent toast spamming
        private const val KICK_COOLDOWN_MS = 2000L
        private var lastKickTime: Long = 0L

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceRunning.value = true
        Log.d(TAG, "AutoKickAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (!TARGET_PACKAGES.contains(packageName)) return

        // Throttle rapid repeated events
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastKickTime < KICK_COOLDOWN_MS) return

        val rootNode = rootInActiveWindow ?: event.source ?: return

        val isShortsActive = when (packageName) {
            "com.google.android.youtube" -> isYouTubeShortsActive(rootNode)
            "com.instagram.android" -> isInstagramReelsActive(rootNode)
            else -> false
        }

        if (isShortsActive) {
            executeAutoKick()
        }
    }

    /**
     * YouTube specific verification:
     * Differentiates the Shorts Player from the YouTube Home Feed (which has the "Shorts" bottom tab).
     */
    private fun isYouTubeShortsActive(rootNode: AccessibilityNodeInfo): Boolean {
        // Strategy 1: Check if the Shorts tab in YouTube's bottom navigation bar is ACTIVELY selected.
        // In YouTube, bottom tabs are labelled "Home", "Shorts", "Subscriptions", "You".
        // If "Shorts" is selected, the tab node or its child has isSelected=true or contentDescription contains "selected"
        val isShortsTabSelected = checkShortsTabSelected(rootNode)
        if (isShortsTabSelected) {
            Log.d(TAG, "YouTube Shorts tab is actively selected!")
            return true
        }

        // Strategy 2: Check for presence of active full-screen Shorts player controls or container IDs
        val hasPlayerContainer = hasMatchingNode(rootNode) { node ->
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            PLAYER_CONTAINER_VIEW_IDS.any { id -> viewId.contains(id) }
        }
        if (hasPlayerContainer) {
            Log.d(TAG, "YouTube Shorts player container view ID detected!")
            return true
        }

        // Strategy 3: Check for Shorts player action buttons (like "Dislike this short", "Remix this video")
        val hasShortsPlayerActions = hasMatchingNode(rootNode) { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            PLAYER_ACTION_KEYWORDS.any { keyword ->
                text.contains(keyword) || desc.contains(keyword)
            }
        }
        if (hasShortsPlayerActions) {
            Log.d(TAG, "YouTube Shorts action buttons detected!")
            return true
        }

        return false
    }

    /**
     * Instagram specific verification:
     * Differentiates Reels Viewer from the main Feed.
     */
    private fun isInstagramReelsActive(rootNode: AccessibilityNodeInfo): Boolean {
        // Check for Reels viewer containers
        val hasReelsContainer = hasMatchingNode(rootNode) { node ->
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            viewId.contains("clips_viewer") ||
                    viewId.contains("reel_viewer") ||
                    viewId.contains("clips_video_container")
        }
        if (hasReelsContainer) {
            Log.d(TAG, "Instagram Reels viewer detected!")
            return true
        }

        // Check for Reels tab actively selected or player exclusive text
        val hasReelsExclusiveContent = hasMatchingNode(rootNode) { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val hasReelsWord = text.contains("reels") || desc.contains("reels")
            val isSelectedTab = (node.isSelected || node.isChecked || desc.contains("selected")) && hasReelsWord
            val hasReelsAction = text.contains("original audio") || desc.contains("original audio") ||
                    text.contains("remix reel") || desc.contains("use template")

            isSelectedTab || hasReelsAction
        }

        return hasReelsExclusiveContent
    }

    /**
     * Checks if a navigation tab with the label "Shorts" is currently in the SELECTED state.
     * Crucially, if the "Shorts" tab is present but NOT selected, this returns false.
     */
    private fun checkShortsTabSelected(rootNode: AccessibilityNodeInfo): Boolean {
        var shortsTabIsSelected = false

        traverseNodes(rootNode) { node ->
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            val matchesShortsLabel = text == "shorts" || desc == "shorts" || desc.startsWith("shorts,") || desc.endsWith(", shorts")

            if (matchesShortsLabel) {
                // Determine if this exact tab item or its parent tab container is SELECTED
                val isSelected = node.isSelected || node.isChecked ||
                        desc.contains("selected") ||
                        (node.parent?.isSelected == true) ||
                        (node.parent?.contentDescription?.toString()?.lowercase()?.contains("selected") == true)

                if (isSelected) {
                    shortsTabIsSelected = true
                }
            }
        }

        return shortsTabIsSelected
    }

    /**
     * Helper to traverse the entire AccessibilityNodeInfo tree and find if any node matches a predicate.
     */
    private fun hasMatchingNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node == null) return false
        if (predicate(node)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasMatchingNode(child, predicate)) {
                return true
            }
        }
        return false
    }

    /**
     * Helper to visit every node in the tree.
     */
    private fun traverseNodes(node: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        action(node)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodes(child, action)
        }
    }

    /**
     * Executes the Auto-Kick action sequence to prevent Picture-in-Picture (PiP) mode:
     * 1. First executes GLOBAL_ACTION_BACK to close the short-form video player & stop playback.
     * 2. After a 350ms delay, executes GLOBAL_ACTION_HOME to return to the launcher home screen.
     * 3. Displays the "Focus! Brain-rot blocked." Toast notification.
     */
    private fun executeAutoKick() {
        lastKickTime = System.currentTimeMillis()
        Log.i(TAG, "Auto-Kick triggered! Executing GLOBAL_ACTION_BACK to dismiss player and stop PiP.")

        try {
            // Step 1: Send BACK action first to terminate the active video player
            performGlobalAction(GLOBAL_ACTION_BACK)

            // Step 2 & 3: Delay 350ms to allow video playback/PiP transition to cancel, then go HOME
            mainHandler.postDelayed({
                try {
                    Log.i(TAG, "Executing GLOBAL_ACTION_HOME after player dismiss delay.")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } catch (e: Exception) {
                    Log.e(TAG, "Error performing GLOBAL_ACTION_HOME: ${e.message}", e)
                }
            }, 350L)

            // Step 4: Display the Toast on the main thread
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "Focus! Brain-rot blocked.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing auto-kick sequence: ${e.message}", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoKickAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        _isServiceRunning.value = false
        Log.d(TAG, "AutoKickAccessibilityService destroyed.")
    }
}
