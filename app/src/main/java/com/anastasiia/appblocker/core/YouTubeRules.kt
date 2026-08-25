package com.anastasiia.appblocker.core

const val YOUTUBE_PACKAGE = "com.google.android.youtube"

enum class YouTubeAction { ALLOW, BACK }

// View-id suffixes of the Shorts player (the full-screen vertical pager), NOT
// the Shorts shelf in the home feed — shelf thumbnails are allowed, only
// opening the player is bounced. Matched by suffix like the Instagram ids.
//
// Captured via uiautomator dump on a real device (YouTube 2026-08); "reel_*" is
// YouTube's internal name for Shorts. reel_time_bar is deliberately NOT here:
// it stays visible on the home feed after leaving the player, so it would
// false-block home.
private val SHORTS_PLAYER_IDS = setOf(
    "reel_recycler",
    "reel_player_page_container",
    "reel_player_underlay",
    "reel_watch_fragment_root",
)

/**
 * Classifies a YouTube screen for "no Shorts" mode: everything is allowed
 * except the Shorts player.
 *
 * @param viewIds view-id resource names present in the accessibility tree.
 * @param visibleIds subset of [viewIds] whose nodes are visible to the user.
 *
 * When visibility data exists it decides alone — YouTube keeps back-stack
 * fragments (including a paused Shorts player) in the tree, so presence-only
 * matching would block screens the user has already left. The presence
 * fallback applies only when the caller has no visibility data at all.
 * Anything unrecognized is allowed: a false positive would make normal
 * videos unwatchable.
 */
fun classifyYouTubeScreen(
    viewIds: Set<String>,
    visibleIds: Set<String> = emptySet(),
): YouTubeAction {
    val visible = visibleIds.mapTo(HashSet()) { it.substringAfterLast('/') }
    if (SHORTS_PLAYER_IDS.any { it in visible }) return YouTubeAction.BACK
    if (visible.isNotEmpty()) return YouTubeAction.ALLOW
    val suffixes = viewIds.mapTo(HashSet()) { it.substringAfterLast('/') }
    if (SHORTS_PLAYER_IDS.any { it in suffixes }) return YouTubeAction.BACK
    return YouTubeAction.ALLOW
}
