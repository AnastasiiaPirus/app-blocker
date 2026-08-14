package com.anastasiia.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anastasiia.appblocker.core.BlockerState
import com.anastasiia.appblocker.core.BlockerStateRepository
import com.anastasiia.appblocker.core.INSTAGRAM_INBOX_DEEP_LINK
import com.anastasiia.appblocker.core.INSTAGRAM_PACKAGE
import com.anastasiia.appblocker.core.INSTAGRAM_TAB_IDS
import com.anastasiia.appblocker.core.InstagramAction
import com.anastasiia.appblocker.core.YOUTUBE_PACKAGE
import com.anastasiia.appblocker.core.YouTubeAction
import com.anastasiia.appblocker.core.blockerDataStore
import com.anastasiia.appblocker.core.classifyInstagramScreen
import com.anastasiia.appblocker.core.classifyYouTubeScreen
import com.anastasiia.appblocker.core.shouldBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "BlockerService"
private const val GUARD_THROTTLE_MS = 600L
private const val GUARD_CONFIRM_DELAY_MS = 350L
private const val FEEDBACK_PILL_MS = 1600L
private const val MAX_SCANNED_NODES = 250

class BlockerService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var state = BlockerState()

    private var collectJob: Job? = null

    /** Views for the currently-shown block overlay, or null if none is showing. */
    private var overlay: OverlayViews? = null

    private class OverlayViews(val root: View, val icon: ImageView, val label: TextView)

    /** Dismisses the overlay when the screen turns off, so it can't linger over the keyguard. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            removeOverlay()
            removeFeedbackPill()
        }
    }

    private var screenOffReceiverRegistered = false

    override fun onServiceConnected() {
        collectJob?.cancel()
        val repository = BlockerStateRepository(applicationContext.blockerDataStore)
        collectJob = scope.launch { repository.state.collect { state = it } }

        if (screenOffReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenOffReceiverRegistered = false
        }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenOffReceiverRegistered = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (shouldBlock(pkg, state, System.currentTimeMillis())) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    showBlockOverlay(pkg)
                } else {
                    guardSpecialFeature(pkg)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> guardSpecialFeature(pkg)
        }
    }

    /** Timestamp of the last back/redirect the guard performed, for throttling. */
    private var lastGuardActionAt = 0L

    private val guardHandler = Handler(Looper.getMainLooper())

    /** Pending confirmation re-scan, or null. At most one is scheduled at a time. */
    private var pendingGuardCheck: Runnable? = null

    /** Unified verdict for the per-app special-feature classifiers. */
    private enum class GuardVerdict { ALLOW, BACK, REDIRECT_INBOX }

    /**
     * Special-feature guards: Instagram "messages only" and YouTube "no
     * Shorts". Classifies the current screen from its view-id tree and bounces
     * blocked surfaces.
     *
     * A blocked classification is never acted on immediately: during screen
     * transitions the tree is a mix of the outgoing and incoming surfaces (e.g.
     * opening a DM thread still shows the inbox's nav tabs before the thread
     * renders), which misreads as a blocked surface. Instead a re-scan is
     * scheduled and the action fires only if the settled screen still
     * classifies as blocked. Actions are also throttled so event bursts can't
     * queue repeated back presses or redirects mid-animation.
     */
    private fun guardSpecialFeature(pkg: String?) {
        if (pkg == null || !guardApplies(pkg)) {
            if (pkg != INSTAGRAM_PACKAGE && pkg != YOUTUBE_PACKAGE) cancelPendingGuardCheck()
            return
        }
        if (pendingGuardCheck != null) return
        if (System.currentTimeMillis() - lastGuardActionAt < GUARD_THROTTLE_MS) return

        if (classifyCurrentScreen(pkg) == GuardVerdict.ALLOW) return

        val check = Runnable {
            pendingGuardCheck = null
            if (!guardApplies(pkg)) return@Runnable
            val confirmed = classifyCurrentScreen(pkg)
            if (confirmed == GuardVerdict.ALLOW) return@Runnable
            lastGuardActionAt = System.currentTimeMillis()
            when (confirmed) {
                GuardVerdict.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                GuardVerdict.REDIRECT_INBOX -> openInstagramInbox()
                GuardVerdict.ALLOW -> Unit
            }
            showGuardFeedback(
                if (pkg == YOUTUBE_PACKAGE) "Shorts blocked" else "Instagram: messages only",
            )
        }
        pendingGuardCheck = check
        guardHandler.postDelayed(check, GUARD_CONFIRM_DELAY_MS)
    }

    /** Whether the special-feature guard is on for [pkg] right now. */
    private fun guardApplies(pkg: String): Boolean {
        val s = state
        if (!s.enabled || s.isPaused(System.currentTimeMillis())) return false
        if (pkg in s.blockedPackages) return false // full block already handles it
        return when (pkg) {
            INSTAGRAM_PACKAGE -> s.instagramMessagesOnly
            YOUTUBE_PACKAGE -> s.youtubeNoShorts
            else -> false
        }
    }

    private fun classifyCurrentScreen(pkg: String): GuardVerdict {
        val root = rootInActiveWindow ?: return GuardVerdict.ALLOW
        if (root.packageName?.toString() != pkg) return GuardVerdict.ALLOW
        val (ids, visibleIds) = collectViewIds(root)
        val verdict = when (pkg) {
            INSTAGRAM_PACKAGE ->
                when (classifyInstagramScreen(ids, selectedTabs(root), visibleIds)) {
                    InstagramAction.ALLOW -> GuardVerdict.ALLOW
                    InstagramAction.BACK -> GuardVerdict.BACK
                    InstagramAction.REDIRECT_INBOX -> GuardVerdict.REDIRECT_INBOX
                }
            YOUTUBE_PACKAGE ->
                when (classifyYouTubeScreen(ids, visibleIds)) {
                    YouTubeAction.ALLOW -> GuardVerdict.ALLOW
                    YouTubeAction.BACK -> GuardVerdict.BACK
                }
            else -> GuardVerdict.ALLOW
        }
        if (verdict != GuardVerdict.ALLOW) {
            Log.d(
                TAG,
                "$pkg guard: $verdict " +
                    "visible=${visibleIds.map { it.substringAfterLast('/') }}",
            )
        }
        return verdict
    }

    /**
     * Which bottom-nav tabs report selected. Selection often sits on a child
     * of the tab node (its icon) rather than the tab itself, so a few levels
     * of descendants are checked too.
     */
    private fun selectedTabs(root: AccessibilityNodeInfo): Set<String> =
        INSTAGRAM_TAB_IDS.filterTo(HashSet()) { tab ->
            root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$tab")
                .any { isSelectedDeep(it, depth = 3) }
        }

    private fun isSelectedDeep(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (node.isSelected) return true
        if (depth == 0) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isSelectedDeep(child, depth - 1)) return true
        }
        return false
    }

    private fun cancelPendingGuardCheck() {
        pendingGuardCheck?.let { guardHandler.removeCallbacks(it) }
        pendingGuardCheck = null
    }

    /** Returns all view ids in the tree plus the subset whose nodes are visible to the user. */
    private fun collectViewIds(root: AccessibilityNodeInfo): Pair<Set<String>, Set<String>> {
        val ids = HashSet<String>()
        val visibleIds = HashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCANNED_NODES) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let {
                ids.add(it)
                if (node.isVisibleToUser) visibleIds.add(it)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return ids to visibleIds
    }

    /** The transient guard-feedback pill currently on screen, or null. */
    private var feedbackPill: View? = null

    /**
     * Shows a small self-dismissing pill so a guard action reads as the
     * blocker acting, not the app glitching. Non-touchable, so it never
     * intercepts input; replaced in place if one is already showing.
     */
    private fun showGuardFeedback(text: String) {
        removeFeedbackPill()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val pill = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE6222222.toInt())
                cornerRadius = dp(24).toFloat()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        }
        windowManager().addView(pill, params)
        feedbackPill = pill
        guardHandler.postDelayed({ if (feedbackPill === pill) removeFeedbackPill() }, FEEDBACK_PILL_MS)
    }

    private fun removeFeedbackPill() {
        val existing = feedbackPill ?: return
        windowManager().removeView(existing)
        feedbackPill = null
    }

    private fun openInstagramInbox() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(INSTAGRAM_INBOX_DEEP_LINK))
                    .setPackage(INSTAGRAM_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * Shows a full-screen accessibility-overlay window with the blocked app's
     * icon/label and a Close button. Only one overlay exists at a time; if one
     * is already showing, its content is replaced (this also prevents a stale
     * label from a previous block when a second app is blocked in quick
     * succession).
     */
    private fun showBlockOverlay(pkg: String?) {
        val (label, icon) = resolveAppInfo(pkg)

        val existing = overlay
        if (existing != null) {
            bindOverlayContent(existing, label, icon)
            return
        }

        val created = buildOverlayViews()
        bindOverlayContent(created, label, icon)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        windowManager().addView(created.root, params)
        overlay = created
    }

    private fun resolveAppInfo(pkg: String?): Pair<String, Drawable?> =
        try {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg ?: "", 0)
            pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info)
        } catch (e: Exception) {
            (pkg ?: "App") to null
        }

    private fun buildOverlayViews(): OverlayViews {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                bottomMargin = dp(16)
            }
        }
        root.addView(iconView)

        val labelView = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(labelView)

        val blockedView = TextView(this).apply {
            text = "Blocked"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(32))
        }
        root.addView(blockedView)

        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener { removeOverlay() }
        }
        root.addView(closeButton)

        return OverlayViews(root, iconView, labelView)
    }

    private fun bindOverlayContent(views: OverlayViews, label: String, icon: Drawable?) {
        views.label.text = label
        if (icon != null) {
            views.icon.setImageDrawable(icon)
            views.icon.visibility = View.VISIBLE
        } else {
            views.icon.visibility = View.GONE
        }
    }

    private fun removeOverlay() {
        val existing = overlay ?: return
        windowManager().removeView(existing.root)
        overlay = null
    }

    private fun windowManager() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cancelPendingGuardCheck()
        if (screenOffReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenOffReceiverRegistered = false
        }
        removeOverlay()
        removeFeedbackPill()
        scope.cancel()
        super.onDestroy()
    }
}
