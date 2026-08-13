package com.anastasiia.appblocker.core

const val INSTAGRAM_PACKAGE = "com.instagram.android"
const val INSTAGRAM_INBOX_DEEP_LINK = "instagram://direct-inbox"

enum class InstagramAction { ALLOW, BACK, REDIRECT_INBOX }

// View-id suffixes observed in com.instagram.android. Matched by suffix because
// Instagram occasionally changes the package prefix but has kept these leaf
// names stable for years. False positives are worse than false negatives here:
// a wrong BLOCK locks the user out of their messages, so unknown screens are
// allowed and only positively identified surfaces are acted on.
private val DM_SURFACE_IDS = setOf(
    "direct_inbox_container",
    "direct_inbox_recycler_view",
    "direct_inbox_action_bar",
    "direct_thread_toolbar",
    "thread_message_list",
    "row_thread_composer_edittext",
)

private const val REELS_VIEWER_ID = "clips_viewer_view_pager"

private val MAIN_NAV_TAB_IDS = setOf("feed_tab", "clips_tab", "search_tab", "profile_tab")

/**
 * Classifies an Instagram screen from the set of view-id resource names present
 * in its accessibility node tree, for "messages only" mode.
 *
 * - DM surfaces are allowed; a reels viewer layered over a DM thread (a shared
 *   reel that was tapped) is dismissed with BACK, which lands back in the thread.
 * - The reels viewer anywhere else, or any surface showing the main bottom nav
 *   (home feed, reels, explore, profile), redirects to the DM inbox.
 */
fun classifyInstagramScreen(viewIds: Set<String>): InstagramAction {
    val suffixes = viewIds.mapTo(HashSet()) { it.substringAfterLast('/') }
    val onDmSurface = DM_SURFACE_IDS.any { it in suffixes }
    val reelsViewerOpen = REELS_VIEWER_ID in suffixes
    return when {
        onDmSurface -> if (reelsViewerOpen) InstagramAction.BACK else InstagramAction.ALLOW
        reelsViewerOpen -> InstagramAction.REDIRECT_INBOX
        MAIN_NAV_TAB_IDS.any { it in suffixes } -> InstagramAction.REDIRECT_INBOX
        else -> InstagramAction.ALLOW
    }
}
