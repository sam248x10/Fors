package com.appcontroller.tool

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ForceStopAccessibilityService : AccessibilityService() {
    private data class Target(val name: String, val packageName: String)

    private data class Request(
        val target: Target,
        val settingsPackage: String,
        val openedAt: Long,
        var appInfoWindowId: Int? = null
    )

    companion object {
        private var connectedService: ForceStopAccessibilityService? = null

        internal fun enqueue(activity: Activity, apps: List<AppItem>): Boolean {
            return connectedService?.startQueue(activity, apps) ?: false
        }

        internal const val EXTRA_RESULT = "com.appcontroller.tool.STOP_RESULT"

        private val stopLabels = listOf("Force stop", "فرض الإيقاف", "فرض إيقاف", "إيقاف إجباري", "إيقاف بالقوة")
        private val confirmationLabels = stopLabels + listOf("OK", "موافق", "إيقاف", "Stop")
        private val cancelLabels = listOf("Cancel", "إلغاء")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Target>()
    private var request: Request? = null
    private var running = false
    private var singleTarget: Target? = null
    private var stopped = 0
    private var skipped = 0
    private val timeout = Runnable { completeTarget(success = false) }
    private val advance = Runnable { openNextTarget() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
    }

    private fun startQueue(activity: Activity, apps: List<AppItem>): Boolean {
        if (running || apps.isEmpty()) return false
        queue.addAll(apps.distinctBy { it.packageName }.map { Target(it.name, it.packageName) })
        singleTarget = queue.singleOrNull()
        stopped = 0
        skipped = 0
        running = true
        openNextTarget(activity)
        return true
    }

    private fun openNextTarget(activity: Activity? = null) {
        if (!running) return
        val target = queue.removeFirstOrNull()
        if (target == null) {
            finishQueue()
            return
        }

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", target.packageName, null)
        )
        val settingsPackage = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        if (settingsPackage == null) {
            completeTarget(success = false)
            return
        }

        request = Request(target, settingsPackage, SystemClock.uptimeMillis())
        try {
            intent.setPackage(settingsPackage)
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            handler.postDelayed(timeout, 6_000L)
        } catch (_: ActivityNotFoundException) {
            completeTarget(success = false)
        } catch (_: SecurityException) {
            completeTarget(success = false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = request ?: return
        if (event == null || event.eventTime < current.openedAt ||
            event.packageName?.toString() != current.settingsPackage
        ) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != current.settingsPackage) return
        val hasCancel = findLabel(root, cancelLabels) != null ||
            root.findAccessibilityNodeInfosByViewId("android:id/button2").orEmpty().any { it.isVisibleToUser }
        if (current.appInfoWindowId != null) {
            if (!hasCancel) return
            val positiveButton = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                .orEmpty().firstOrNull { it.isVisibleToUser && matchesLabel(it, confirmationLabels) }
            val confirmationNode = positiveButton ?: if (root.windowId != current.appInfoWindowId) {
                findLabel(root, confirmationLabels)
            } else {
                null
            }
            val confirmation = findClickTarget(confirmationNode) ?: return
            if (confirmation.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                completeTarget(success = true)
            }
            return
        }

        if (hasCancel || findLabel(root, listOf(current.target.name)) == null) return
        val stopNode = findLabel(root, stopLabels) ?: return
        val button = findClickTarget(stopNode)
        if (button == null) {
            completeTarget(success = false)
            return
        }
        current.appInfoWindowId = root.windowId
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            completeTarget(success = false)
        }
    }

    private fun completeTarget(success: Boolean) {
        request = null
        handler.removeCallbacks(timeout)
        if (success) stopped++ else skipped++
        handler.postDelayed(advance, 350L)
    }

    private fun finishQueue() {
        running = false
        val target = singleTarget
        singleTarget = null
        val message = when {
            target == null -> "اكتملت العملية. تم إيقاف: $stopped. تعذّر إيقاف: $skipped."
            stopped == 1 -> "تم فرض إيقاف ${target.name}."
            else -> "تعذّر فرض إيقاف ${target.name}."
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RESULT, message)
        )
    }

    private fun findLabel(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            val match = root.findAccessibilityNodeInfosByText(label).orEmpty().firstOrNull {
                it.isVisibleToUser && matchesLabel(it, listOf(label))
            }
            if (match != null) return match
        }
        return null
    }

    private fun matchesLabel(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.trim()
        val description = node.contentDescription?.toString()?.trim()
        return labels.any { text.equals(it, ignoreCase = true) || description.equals(it, ignoreCase = true) }
    }

    private fun findClickTarget(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var candidate = node
        while (candidate != null && candidate.isEnabled && candidate.isVisibleToUser) {
            if (candidate.isClickable) return candidate
            candidate = candidate.parent
        }
        return null
    }

    override fun onInterrupt() {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        skipped += queue.size + if (request != null) 1 else 0
        queue.clear()
        request = null
        finishQueue()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }
}
