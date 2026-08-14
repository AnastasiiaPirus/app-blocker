package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeRulesTest {
    private fun ids(vararg suffixes: String) =
        suffixes.map { "com.google.android.youtube:id/$it" }.toSet()

    // A home feed tree with a Shorts shelf: shelf thumbnails must NOT trigger,
    // only the actual Shorts player.
    private val homeFeedTree = ids(
        "results", "toolbar", "pivot_bar",
        "shorts_shelf", "shorts_lockup_image", "thumbnail",
    )

    // Captured via uiautomator dump on the Pixel 9 (YouTube 2026-08) with the
    // Shorts player open.
    private val shortsPlayerTree = ids(
        "pivot_bar", "reel_recycler", "reel_player_page_container",
        "reel_player_underlay", "reel_watch_fragment_root", "reel_time_bar",
    )

    @Test fun shortsPlayerVisibleGoesBack() =
        assertEquals(
            YouTubeAction.BACK,
            classifyYouTubeScreen(shortsPlayerTree, visibleIds = ids("reel_recycler")),
        )

    @Test fun homeFeedWithShortsShelfIsAllowed() =
        assertEquals(
            YouTubeAction.ALLOW,
            classifyYouTubeScreen(homeFeedTree, visibleIds = ids("results", "shorts_shelf")),
        )

    @Test fun regularVideoPlaybackIsAllowed() =
        assertEquals(
            YouTubeAction.ALLOW,
            classifyYouTubeScreen(
                ids("watch_player", "player_view", "engagement_panel"),
                visibleIds = ids("watch_player", "player_view"),
            ),
        )

    // Observed on-device: after backing out of the Shorts player,
    // reel_time_bar stays VISIBLE on the home feed (a lingering back-stack
    // node). It must never become a trigger id or home would false-block.
    @Test fun lingeringReelTimeBarOnHomeFeedIsAllowed() =
        assertEquals(
            YouTubeAction.ALLOW,
            classifyYouTubeScreen(
                homeFeedTree + ids("reel_time_bar"),
                visibleIds = ids("results", "reel_time_bar"),
            ),
        )

    @Test fun shortsInTreeButNotVisibleIsAllowed() =
        assertEquals(
            YouTubeAction.ALLOW,
            classifyYouTubeScreen(
                homeFeedTree + ids("reel_recycler"),
                visibleIds = ids("results"),
            ),
        )

    @Test fun presenceFallbackWithoutVisibilityDataGoesBack() =
        assertEquals(
            YouTubeAction.BACK,
            classifyYouTubeScreen(shortsPlayerTree),
        )

    @Test fun unknownScreenIsAllowed() =
        assertEquals(YouTubeAction.ALLOW, classifyYouTubeScreen(ids("some_future_id")))

    @Test fun emptyTreeIsAllowed() =
        assertEquals(YouTubeAction.ALLOW, classifyYouTubeScreen(emptySet()))

    @Test fun ignoresIdsFromOtherNamespacesWithSimilarNames() =
        assertEquals(
            YouTubeAction.ALLOW,
            classifyYouTubeScreen(setOf("com.google.android.youtube:id/my_reel_recycler_thing")),
        )
}
