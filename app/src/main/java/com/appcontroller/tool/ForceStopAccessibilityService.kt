package com.appcontroller.tool

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ForceStopAccessibilityService : AccessibilityService() {
    private data class Request(
        val appName: String,
        val settingsPackage: String,
        val expiresAt: Long,
        val confirmationWindowFrom: Int? = null
    )

    companion object {
        private var pendingRequest: Request? = null

        internal fun prepare(app: AppItem, settingsPackage: String) {
            pendingRequest = Request(app.name, settingsPackage, SystemClock.elapsedRealtime() + 5_000L)
        }

        internal fun clear() {
            pendingRequest = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = pendingRequest ?: return
        if (SystemClock.elapsedRealtime() >= request.expiresAt) {
            clear()
            return
        }
        if (event?.packageName?.toString() != request.settingsPackage) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != request.settingsPackage) return
        val hasCancel = listOf("Cancel", "إلغاء").any { containsText(root, it) }
        if (request.confirmationWindowFrom != null) {
            if (!hasCancel || root.windowId == request.confirmationWindowFrom) return
            val confirmation = findButton(root, listOf("Force stop", "فرض الإيقاف", "إيقاف إجباري", "OK", "موافق"))
                ?: return
            clear()
            confirmation.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        if (hasCancel || !containsText(root, request.appName)) return
        val button = findButton(root, listOf("Force stop", "فرض الإيقاف", "إيقاف إجباري")) ?: return
        pendingRequest = request.copy(confirmationWindowFrom = root.windowId)
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) clear()
    }

    private fun containsText(root: AccessibilityNodeInfo, label: String): Boolean {
        return root.findAccessibilityNodeInfosByText(label).orEmpty().any {
            it.isVisibleToUser && it.text?.toString()?.trim().equals(label, ignoreCase = true)
        }
    }

    private fun findButton(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            for (node in root.findAccessibilityNodeInfosByText(label).orEmpty()) {
                if (!node.isVisibleToUser || !node.isEnabled) continue
                if (!node.text?.toString()?.trim().equals(label, ignoreCase = true) &&
                    !node.contentDescription?.toString()?.trim().equals(label, ignoreCase = true)
                ) continue

                var candidate: AccessibilityNodeInfo? = node
                while (candidate != null && candidate.isEnabled && candidate.isVisibleToUser) {
                    if (candidate.isClickable) return candidate
                    candidate = candidate.parent
                }
            }
        }
        return null
    }

    override fun onInterrupt() {
        clear()
    }

    override fun onDestroy() {
        clear()
        super.onDestroy()
    }
}
