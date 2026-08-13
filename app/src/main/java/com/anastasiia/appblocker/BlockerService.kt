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
import android.net.Uri
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
import com.anastasiia.appblocker.core.InstagramAction
import com.anastasiia.appblocker.core.blockerDataStore
import com.anastasiia.appblocker.core.classifyInstagramScreen
import com.anastasiia.appblocker.core.shouldBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val GUARD_THROTTLE_MS = 600L
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
                    guardInstagram(pkg)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> guardInstagram(pkg)
        }
    }

    /** Timestamp of the last back/redirect the guard performed, for throttling. */
    private var lastGuardActionAt = 0L

    /**
     * "Messages only" mode for Instagram: classify the current screen from its
     * view-id tree and bounce anything that isn't a DM surface. Throttled so a
     * burst of content-changed events can't queue up repeated back presses or
     * redirects while Instagram is still animating away from the last one.
     */
    private fun guardInstagram(pkg: String?) {
        if (pkg != INSTAGRAM_PACKAGE) return
        val s = state
        if (!s.enabled || !s.instagramMessagesOnly || s.isPaused(System.currentTimeMillis())) return
        if (INSTAGRAM_PACKAGE in s.blockedPackages) return // full block already handles it

        val now = System.currentTimeMillis()
        if (now - lastGuardActionAt < GUARD_THROTTLE_MS) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != INSTAGRAM_PACKAGE) return

        when (classifyInstagramScreen(collectViewIds(root))) {
            InstagramAction.ALLOW -> Unit
            InstagramAction.BACK -> {
                lastGuardActionAt = now
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            InstagramAction.REDIRECT_INBOX -> {
                lastGuardActionAt = now
                openInstagramInbox()
            }
        }
    }

    private fun collectViewIds(root: AccessibilityNodeInfo): Set<String> {
        val ids = HashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCANNED_NODES) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { ids.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return ids
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
        if (screenOffReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenOffReceiverRegistered = false
        }
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }
}
