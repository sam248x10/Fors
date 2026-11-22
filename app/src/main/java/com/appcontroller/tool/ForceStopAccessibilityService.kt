package com.appcontroller.tool

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

class ForceStopAccessibilityService : AccessibilityService() {
    private data class Target(val name: String, val packageName: String)

    private data class Request(
        val target: Target,
        val settingsPackage: String,
        val openedAt: Long,
        var appInfoWindowId: Int? = null,
        var stopClicked: Boolean = false,
        var confirmationClicked: Boolean = false
    )

    companion object {
        private var connectedService: ForceStopAccessibilityService? = null

        internal fun enqueue(activity: Activity, apps: List<AppItem>): Boolean {
            val targets = apps.map { Target(it.name, it.packageName) }
            return connectedService?.startQueue(activity, targets) ?: false
        }

        internal fun enqueueScheduled(packages: Set<String>) {
            val service = connectedService ?: return
            val targets = packages.filter { it != service.packageName }.mapNotNull { name ->
                try {
                    val info = service.packageManager.getApplicationInfo(name, 0)
                    if (info.flags and ApplicationInfo.FLAG_STOPPED != 0) null
                    else Target(service.packageManager.getApplicationLabel(info).toString(), name)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
            service.startQueue(null, targets)
        }
        internal const val EXTRA_RESULT = "com.appcontroller.tool.STOP_RESULT"
        internal const val EXTRA_CONTINUE = "com.appcontroller.tool.STOP_CONTINUE"

        internal fun continueQueue(activity: Activity) {
            val service = connectedService ?: return
            if (service.running && service.request == null && service.queue.isNotEmpty()) {
                service.openNextTarget(activity)
            }
        }

        internal fun controllerResumed() {
            val service = connectedService ?: return
            if (!service.running) service.hideProgress()
        }

        private val stopLabels = listOf("Force stop", "فرض الإيقاف", "فرض إيقاف", "إيقاف إجباري", "إيقاف بالقوة")
        private val confirmationLabels = listOf("OK", "موافق", "إيقاف", "Stop") + stopLabels
        private val cancelLabels = listOf("Cancel", "إلغاء")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val queue = ArrayDeque<Target>()
    private var request: Request? = null
    private var running = false
    private var singleTarget: Target? = null
    private var stopped = 0
    private var skipped = 0
    private var alreadyStopped = 0
    private var total = 0
    private var cancelled = false
    private var progressOverlay: FrameLayout? = null
    private val dismissOverlay = Runnable { hideProgress() }
    private var progressLabel: TextView? = null
    private var progressBar: ProgressBar? = null
    private val failures = mutableListOf<String>()
    private val timeout = Runnable {
        val current = request
        if (current != null) {
            val reason = when {
                current.confirmationClicked -> "لم يؤكّد أندرويد أن التطبيق أصبح موقوفًا"
                current.stopClicked -> "لم تُلتقط نافذة التأكيد"
                else -> "زر فرض الإيقاف غير متاح أو لم يظهر"
            }
            failures.add("${current.target.name}: $reason")
        }
        completeTarget(success = false)
    }
    private val poll = object : Runnable {
        override fun run() {
            val current = request ?: return
            inspectSettings(current)
            if (request === current) handler.postDelayed(this, 250L)
        }
    }
    private val advance = Runnable {
        if (queue.isEmpty()) finishQueue() else returnToController()
    }
    private val closeSettings = Runnable {
        val current = requestToClose
        requestToClose = null
        val root = current?.let { settingsRoot(it.settingsPackage) }
        val settingsStillOpen = current != null && root != null &&
            root.packageName?.toString() == current.settingsPackage &&
            (root.windowId == current.appInfoWindowId ||
                findLabel(root, listOf(current.target.name)) != null)
        if (settingsStillOpen && performGlobalAction(GLOBAL_ACTION_BACK)) {
            handler.postDelayed(advance, 250L)
        } else {
            handler.post(advance)
        }
    }
    private var requestToClose: Request? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
    }

    private fun startQueue(activity: Activity?, apps: List<Target>): Boolean {
        if (running || apps.isEmpty()) return false
        queue.addAll(apps.distinctBy { it.packageName })
        singleTarget = queue.singleOrNull()
        stopped = 0
        skipped = 0
        alreadyStopped = 0
        total = queue.size
        cancelled = false
        failures.clear()
        running = true
        if (total > 1 && !showProgress()) {
            queue.clear()
            running = false
            returnToController("تعذّر عرض شاشة التقدّم. لم يبدأ الإيقاف الجماعي.")
            return true
        }
        openNextTarget(activity)
        return true
    }

    private fun openNextTarget(activity: Activity?) {
        if (!running) return
        var target = queue.removeFirstOrNull()
        while (target != null) {
            try {
                if (!isStopped(target.packageName)) break
                alreadyStopped++
            } catch (_: PackageManager.NameNotFoundException) {
                skipped++
                failures.add("${target.name}: التطبيق غير متاح")
            }
            target = queue.removeFirstOrNull()
        }
        if (target == null) {
            finishQueue()
            return
        }
        progressLabel?.text = "جاري إيقاف ${target.name} · ${total - queue.size} من $total"
        progressBar?.progress = total - queue.size - 1

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", target.packageName, null)
        )
        val settingsPackage = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        if (settingsPackage == null) {
            failures.add("${target.name}: تعذّر فتح صفحة معلومات التطبيق")
            completeTarget(success = false)
            return
        }

        request = Request(target, settingsPackage, SystemClock.uptimeMillis())
        try {
            intent.setPackage(settingsPackage)
            if (activity == null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else activity.startActivity(intent)
            handler.postDelayed(poll, 250L)
            handler.postDelayed(timeout, 10_000L)
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
        inspectSettings(current)
    }

    private fun inspectSettings(current: Request) {
        try {
            if (isStopped(current.target.packageName)) {
                completeTarget(success = true)
                return
            }
        } catch (_: PackageManager.NameNotFoundException) {
            completeTarget(success = false)
            return
        }
        if (current.confirmationClicked) return
        val root = settingsRoot(current.settingsPackage) ?: return
        val hasCancel = findLabel(root, cancelLabels) != null ||
            root.findAccessibilityNodeInfosByViewId("android:id/button2").orEmpty().any { it.isVisibleToUser }
        if (current.stopClicked) {
            if (!hasCancel) return
            val positiveButton = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                .orEmpty().firstOrNull { it.isVisibleToUser && matchesLabel(it, confirmationLabels) }
            val confirmation = findClickTarget(positiveButton) ?: confirmationLabels.firstNotNullOfOrNull { label ->
                if (root.windowId == current.appInfoWindowId && label in stopLabels) {
                    null
                } else {
                    root.findAccessibilityNodeInfosByText(label).orEmpty().firstNotNullOfOrNull { node ->
                        if (matchesLabel(node, listOf(label))) findClickTarget(node) else null
                    }
                }
            } ?: return
            if (confirmation.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                current.confirmationClicked = true
            }
            return
        }

        if (hasCancel || findLabel(root, listOf(current.target.name)) == null) return
        current.appInfoWindowId = root.windowId
        val stopNode = findLabel(root, stopLabels) ?: return
        val button = findClickTarget(stopNode)
        if (button == null) return
        current.stopClicked = true
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            completeTarget(success = false)
        }
    }

    private fun settingsRoot(settingsPackage: String): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == settingsPackage) return active
        return windows.orEmpty().asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == settingsPackage }
    }

    private fun isStopped(packageName: String): Boolean =
        packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_STOPPED != 0

    private fun completeTarget(success: Boolean) {
        requestToClose = request
        request = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(poll)
        if (success) stopped++ else skipped++
        if (requestToClose == null) handler.post(advance)
        else handler.postDelayed(closeSettings, 250L)
    }

    private fun finishQueue() {
        running = false
        val target = singleTarget
        singleTarget = null
        val message = when {
            cancelled -> "أُلغيت العملية. تم إيقاف: $stopped. موقوف مسبقًا: $alreadyStopped."
            target == null -> "تم إيقاف: $stopped. موقوف مسبقًا: $alreadyStopped. تعذّر الإيقاف: $skipped."
            alreadyStopped == 1 -> "${target.name} موقوف بالفعل."
            stopped == 1 -> "تم فرض إيقاف ${target.name}."
            else -> "تعذّر فرض إيقاف ${target.name}."
        }
        val details = failures.joinToString("\n")
        progressBar?.progress = total
        progressLabel?.text = "جاري العودة إلى التطبيق…"
        if (progressOverlay != null) handler.postDelayed(dismissOverlay, 2_000L)
        returnToController(if (details.isEmpty()) message else "$message\n\n$details")
    }

    private fun showProgress(): Boolean {
        val font = ResourcesCompat.getFont(this, R.font.cairo)
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (14 * density).toInt()
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(0xFF211A11.toInt())
                setStroke((density + 0.5f).toInt(), 0xFFF3C969.toInt())
                cornerRadius = 14 * density
            }
            progressLabel = TextView(context).apply {
                typeface = font
                textSize = 15f
                text = "جاري فحص التطبيقات…"
                setTextColor(0xFFFFF4DD.toInt())
            }.also { addView(it) }
            progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = total
                progressTintList = android.content.res.ColorStateList.valueOf(0xFFF3C969.toInt())
            }.also { addView(it, LinearLayout.LayoutParams(-1, -2)) }
            addView(Button(context).apply {
                typeface = font
                text = "إلغاء"
                setOnClickListener { cancelQueue() }
            })
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF101010.toInt())
            isClickable = true
            val cardWidth = (resources.displayMetrics.widthPixels - 32 * density).toInt()
                .coerceAtMost((360 * density).toInt())
            addView(card, FrameLayout.LayoutParams(
                cardWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        try {
            windowManager.addView(overlay, params)
            progressOverlay = overlay
        } catch (_: WindowManager.BadTokenException) {
            hideProgress()
        } catch (_: SecurityException) {
            hideProgress()
        }
        return progressOverlay != null
    }

    private fun hideProgress() {
        handler.removeCallbacks(dismissOverlay)
        progressOverlay?.let { windowManager.removeView(it) }
        progressOverlay = null
        progressLabel = null
        progressBar = null
    }

    private fun cancelQueue() {
        if (!running || cancelled) return
        cancelled = true
        handler.removeCallbacksAndMessages(null)
        requestToClose = request ?: requestToClose
        request = null
        queue.clear()
        handler.post(closeSettings)
    }

    private fun returnToController(message: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (message == null) intent.putExtra(EXTRA_CONTINUE, true) else intent.putExtra(EXTRA_RESULT, message)
        startActivity(intent)
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
        cancelQueue()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideProgress()
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }
}
