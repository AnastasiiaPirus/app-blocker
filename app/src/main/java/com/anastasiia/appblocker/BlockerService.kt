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
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anastasiia.appblocker.core.BlockerState
import com.anastasiia.appblocker.core.BlockerStateRepository
import com.anastasiia.appblocker.core.INSTAGRAM_INBOX_DEEP_LINK
import com.anastasiia.appblocker.core.INSTAGRAM_PACKAGE
import com.anastasiia.appblocker.core.GuardTarget
import com.anastasiia.appblocker.core.INSTAGRAM_TAB_IDS
import com.anastasiia.appblocker.core.InstagramAction
import com.anastasiia.appblocker.core.YOUTUBE_PACKAGE
import com.anastasiia.appblocker.core.YouTubeAction
import com.anastasiia.appblocker.core.blockerDataStore
import com.anastasiia.appblocker.core.classifyInstagramScreen
import com.anastasiia.appblocker.core.classifyYouTubeScreen
import com.anastasiia.appblocker.core.resolveGuardTarget
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

/** Launcher3 Overview view-id suffix of a task card's thumbnail node; its content description is the app label. */
private const val OVERVIEW_SNAPSHOT_ID = "snapshot"

/** How long after a blocked screen (or a preview action) an app's Overview card still counts as that screen. */
private const val PREVIEW_ARM_MS = 10_000L

/** How long no guarded app must be seen before the preview overlay is removed. */
private const val PREVIEW_OVERLAY_DROP_MS = 1_500L

class BlockerService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var state = BlockerState()

    private var collectJob: Job? = null

    /** Views for the currently-shown block overlay, or null if none is showing. */
    private var overlay: OverlayViews? = null

    /** True when the showing overlay was raised by the preview guard, so it is dropped once the preview is gone. */
    private var overlayFromPreview = false

    private class OverlayViews(val root: View, val icon: ImageView, val label: TextView, val headline: TextView)

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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> guardSpecialFeature(pkg)
        }
    }

    /** Timestamp of the last back/redirect the guard performed, for throttling. */
    private var lastGuardActionAt = 0L

    private val guardHandler = Handler(Looper.getMainLooper())

    /** Pending confirmation re-scan, or null. At most one is scheduled at a time. */
    private var pendingGuardCheck: Runnable? = null

    /** Scheduled removal of the preview overlay, or null. */
    private var pendingOverlayDrop: Runnable? = null

    /** Unified verdict for the per-app special-feature classifiers. */
    private enum class GuardVerdict { ALLOW, BACK, REDIRECT_INBOX }

    /**
     * Special-feature guards: Instagram "messages only" and YouTube "no
     * Shorts". Classifies the guarded app's screen from its view-id tree and
     * bounces blocked surfaces.
     *
     * The guarded app is normally the active window. It can also be visible
     * *behind* the launcher: swiping up and holding (or landing in Overview)
     * keeps the app resumed and playing inside its task preview while the
     * launcher owns the active window. That used to disarm the guard entirely
     * — the launcher's event cancelled the pending check — which is how a reel
     * could keep playing in the preview card. Now the guarded app is looked up
     * among all visible windows, and a blocked surface seen in a preview is
     * covered with the block overlay and sent home.
     *
     * A blocked classification is never acted on immediately: during screen
     * transitions the tree is a mix of the outgoing and incoming surfaces (e.g.
     * opening a DM thread still shows the inbox's nav tabs before the thread
     * renders), which misreads as a blocked surface. Instead a re-scan is
     * scheduled and the action fires only if the settled screen still
     * classifies as blocked. Actions are also throttled so event bursts can't
     * queue repeated back presses or redirects mid-animation.
     */
    private fun guardSpecialFeature(eventPkg: String?) {
        val target = currentGuardTarget()
        if (target == null) {
            cancelPendingGuardCheck()
            // The Overview tree flickers while it animates, so the overlay is only
            // dropped once no guarded app has been seen for a moment.
            if (overlayFromPreview && pendingOverlayDrop == null) {
                val drop = Runnable {
                    pendingOverlayDrop = null
                    if (overlayFromPreview && currentGuardTarget() == null) removeOverlay()
                }
                pendingOverlayDrop = drop
                guardHandler.postDelayed(drop, PREVIEW_OVERLAY_DROP_MS)
            }
            return
        }
        pendingOverlayDrop?.let { guardHandler.removeCallbacks(it); pendingOverlayDrop = null }
        if (pendingGuardCheck != null) return
        if (System.currentTimeMillis() - lastGuardActionAt < GUARD_THROTTLE_MS) return

        if (classifyTarget(target) == GuardVerdict.ALLOW) return

        val check = Runnable {
            pendingGuardCheck = null
            val settled = currentGuardTarget() ?: return@Runnable
            if (settled.pkg != target.pkg) return@Runnable
            val confirmed = classifyTarget(settled)
            if (confirmed == GuardVerdict.ALLOW) return@Runnable
            lastGuardActionAt = System.currentTimeMillis()
            if (settled.preview) {
                // Keys don't reach a task preview while the hold gesture is in
                // progress, so cover it, then go home so the app gets paused.
                val firstTime = !overlayFromPreview
                previewArmedUntil[settled.pkg] = System.currentTimeMillis() + PREVIEW_ARM_MS
                showGuardOverlay(settled.pkg)
                // HOME is ignored while the hold gesture is in progress and lands
                // once the finger lifts; until then the overlay covers the card.
                performGlobalAction(GLOBAL_ACTION_HOME)
                if (firstTime) showGuardFeedback(guardLabel(settled.pkg))
            } else {
                when (confirmed) {
                    GuardVerdict.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                    GuardVerdict.REDIRECT_INBOX -> openInstagramInbox()
                    GuardVerdict.ALLOW -> Unit
                }
                showGuardFeedback(guardLabel(settled.pkg))
            }
            Log.d(TAG, "${settled.pkg} guard acted: $confirmed preview=${settled.preview} (event from $eventPkg)")
        }
        pendingGuardCheck = check
        guardHandler.postDelayed(check, GUARD_CONFIRM_DELAY_MS)
    }

    private fun guardLabel(pkg: String) =
        if (pkg == YOUTUBE_PACKAGE) "Shorts blocked" else "Instagram: messages only"

    /**
     * The guarded app on screen right now, if any: the active window when it
     * is a guarded app, otherwise a guarded app visible in another window
     * (a task preview). Cheap when no guard mode is on.
     */
    private fun currentGuardTarget(): GuardTarget? {
        val s = state
        if (!s.enabled || s.isPaused(System.currentTimeMillis())) return null
        if (!s.instagramMessagesOnly && !s.youtubeNoShorts) return null
        val activeRoot = rootInActiveWindow
        val active = activeRoot?.packageName?.toString()
        if (active != null && guardApplies(active)) return GuardTarget(active, preview = false)
        val visible = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.packageName?.toString() }
        val nowArm = System.currentTimeMillis()
        val cards = if (activeRoot != null) {
            overviewCardPackages(activeRoot).filter { (previewArmedUntil[it] ?: 0L) > nowArm }
        } else {
            emptyList()
        }
        val target = resolveGuardTarget(active, visible, ::guardApplies, previewCardPkgs = cards)
        // While the card is on screen and we are armed for it, keep the arming alive so a long hold stays covered.
        if (target != null && target.preview && target.pkg in cards) previewArmedUntil[target.pkg] = nowArm + PREVIEW_ARM_MS
        return target
    }

    /**
     * Guarded apps that have a card in the launcher's Overview (or the
     * swipe-up-and-hold quick-switch state). Cards are labelled with the app
     * name, matched against the guarded apps' labels. The tree our service
     * receives does not include the live-tile node that would tell a running
     * card from a parked one, so the caller arms this per app for a short
     * time after a blocked screen was seen.
     */
    private fun overviewCardPackages(launcherRoot: AccessibilityNodeInfo): List<String> {
        val labels = guardedLabels()
        if (labels.isEmpty()) return emptyList()
        val found = ArrayList<String>(1)
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(launcherRoot) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCANNED_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.viewIdResourceName?.endsWith("/$OVERVIEW_SNAPSHOT_ID") == true) {
                val label = node.contentDescription?.toString()?.trim()?.lowercase()
                labels[label]?.let { if (it !in found) found.add(it) }
                continue
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return found
    }

    private var guardedLabelCache: Map<String, String>? = null

    /** Lower-cased app label -> package, for the guarded apps that are installed. */
    private fun guardedLabels(): Map<String, String> {
        guardedLabelCache?.let { return it }
        val map = HashMap<String, String>()
        for (pkg in listOf(INSTAGRAM_PACKAGE, YOUTUBE_PACKAGE)) {
            runCatching {
                val info = packageManager.getApplicationInfo(pkg, 0)
                map[packageManager.getApplicationLabel(info).toString().trim().lowercase()] = pkg
            }
        }
        guardedLabelCache = map
        return map
    }

    /** Most recent verdict for each guarded app's real screen; what the preview falls back to. */
    private val lastVerdict = HashMap<String, GuardVerdict>()

    /** Per guarded app: until when its Overview card is treated as the blocked screen last seen on it. */
    private val previewArmedUntil = HashMap<String, Long>()

    private fun classifyTarget(target: GuardTarget): GuardVerdict {
        val root = if (target.preview) {
            windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.root?.packageName?.toString() == target.pkg
            }?.root
        } else {
            rootInActiveWindow
        }
        if (root == null) {
            // Overview has settled: the app's window is gone from the list, but its
            // card is on screen. Judge it by the last screen we saw it on.
            val remembered = if (target.preview) lastVerdict[target.pkg] ?: GuardVerdict.ALLOW else GuardVerdict.ALLOW
            if (remembered != GuardVerdict.ALLOW) Log.d(TAG, "${target.pkg} live in Overview, last seen: $remembered")
            return remembered
        }
        val verdict = classifyRoot(root, target.pkg)
        if (target.preview && verdict != GuardVerdict.ALLOW) Log.d(TAG, "${target.pkg} seen in task preview: $verdict")
        return verdict
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

    private fun classifyRoot(root: AccessibilityNodeInfo, pkg: String): GuardVerdict {
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
        lastVerdict[pkg] = verdict
        if (verdict != GuardVerdict.ALLOW) previewArmedUntil[pkg] = System.currentTimeMillis() + PREVIEW_ARM_MS
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

    private fun cancelPendingOverlayDrop() {
        pendingOverlayDrop?.let { guardHandler.removeCallbacks(it) }
        pendingOverlayDrop = null
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
        overlayFromPreview = false
        showOverlay(pkg, headline = "Blocked")
    }

    /** The guard's overlay over a task preview: same screen, mode-specific headline. */
    private fun showGuardOverlay(pkg: String) {
        overlayFromPreview = true
        showOverlay(pkg, headline = if (pkg == YOUTUBE_PACKAGE) "No Shorts" else "Messages only")
    }

    private fun showOverlay(pkg: String?, headline: String) {
        val (label, icon) = resolveAppInfo(pkg)

        val existing = overlay
        if (existing != null) {
            bindOverlayContent(existing, label, icon, headline)
            return
        }

        val created = buildOverlayViews()
        bindOverlayContent(created, label, icon, headline)

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

        return OverlayViews(root, iconView, labelView, blockedView)
    }

    private fun bindOverlayContent(views: OverlayViews, label: String, icon: Drawable?, headline: String) {
        views.label.text = label
        views.headline.text = headline
        if (icon != null) {
            views.icon.setImageDrawable(icon)
            views.icon.visibility = View.VISIBLE
        } else {
            views.icon.visibility = View.GONE
        }
    }

    private fun removeOverlay() {
        overlayFromPreview = false
        val existing = overlay ?: return
        windowManager().removeView(existing.root)
        overlay = null
    }

    private fun windowManager() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cancelPendingGuardCheck()
        cancelPendingOverlayDrop()
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
