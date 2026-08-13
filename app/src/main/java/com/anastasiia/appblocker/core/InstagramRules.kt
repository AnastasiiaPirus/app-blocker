package com.anastasiia.appblocker.core

const val INSTAGRAM_PACKAGE = "com.instagram.android"
const val INSTAGRAM_INBOX_DEEP_LINK = "instagram://direct-inbox"

enum class InstagramAction { ALLOW, BACK, REDIRECT_INBOX }

/** Bottom-nav tab ids. Selection state on these (or a descendant) identifies the visible surface. */
val INSTAGRAM_TAB_IDS = setOf("feed_tab", "clips_tab", "search_tab", "direct_tab", "profile_tab")

// View-id suffixes observed in com.instagram.android, matched by suffix because
// the package prefix occasionally changes while leaf names stay stable.
//
// Current Instagram builds render the main surfaces inside one swipeable pager
// and keep the OTHER tabs' subtrees (feed rows, clips viewer, inbox list) alive
// in the node tree behind the visible one, so mere PRESENCE of an id cannot
// tell the inbox apart from the feed. The selected bottom-nav tab is the only
// reliable surface signal there; presence rules below are fallbacks for
// screens that have no bottom nav (DM threads, modal reel viewers) and for
// older Instagram versions. False positives are worse than false negatives —
// a wrong block locks the user out of messages — so unknowns are allowed.
private val DM_SURFACE_IDS = setOf(
    "direct_inbox_container",
    "direct_inbox_recycler_view",
    "direct_inbox_action_bar",
    "direct_thread_toolbar",
    "thread_message_list",
    "row_thread_composer_edittext",
)

private const val REELS_VIEWER_ID = "clips_viewer_view_pager"

/**
 * Classifies an Instagram screen for "messages only" mode.
 *
 * @param viewIds view-id resource names present in the accessibility tree.
 * @param selectedTabs suffixes from [INSTAGRAM_TAB_IDS] whose node (or a close
 *   descendant) reports selected — normally zero or one entry.
 *
 * Rules, in order:
 * - Direct tab selected → the inbox (or a surface layered on it) → allow.
 * - Any other tab selected → feed/reels/explore/profile → redirect to inbox.
 * - Nav tabs present but none detectably selected → can't tell → fail open.
 * - No nav (fullscreen screens): DM surfaces allow, with a reels viewer over a
 *   DM thread (a tapped shared reel) dismissed via BACK; a reels viewer alone
 *   is the modal viewer (e.g. opened from a DM share on current builds) → BACK,
 *   which lands back where it was opened from.
 */
fun classifyInstagramScreen(
    viewIds: Set<String>,
    selectedTabs: Set<String> = emptySet(),
): InstagramAction {
    val suffixes = viewIds.mapTo(HashSet()) { it.substringAfterLast('/') }
    val onDmSurface = DM_SURFACE_IDS.any { it in suffixes }
    val reelsViewerOpen = REELS_VIEWER_ID in suffixes
    return when {
        "direct_tab" in selectedTabs -> InstagramAction.ALLOW
        selectedTabs.isNotEmpty() -> InstagramAction.REDIRECT_INBOX
        INSTAGRAM_TAB_IDS.any { it in suffixes } -> InstagramAction.ALLOW
        onDmSurface -> if (reelsViewerOpen) InstagramAction.BACK else InstagramAction.ALLOW
        reelsViewerOpen -> InstagramAction.BACK
        else -> InstagramAction.ALLOW
    }
}
