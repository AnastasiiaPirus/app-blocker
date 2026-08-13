package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramRulesTest {
    private fun ids(vararg suffixes: String) =
        suffixes.map { "com.instagram.android:id/$it" }.toSet()

    // Captured from a real device (Instagram ~2026): the swipeable pager keeps
    // feed rows AND the clips viewer AND the inbox list in the tree at once,
    // whichever tab is showing.
    private val pagerTree = ids(
        "feed_tab", "clips_tab", "search_tab", "direct_tab", "profile_tab",
        "swipeable_tab_view_pager", "inbox_refreshable_thread_list_recyclerview",
        "row_feed_photo_imageview", "row_feed_button_like",
        "clips_viewer_view_pager", "clips_video_container", "like_button",
    )

    @Test fun inboxTabSelectedIsAllowedDespiteFeedInTree() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(pagerTree, selectedTabs = setOf("direct_tab")),
        )

    @Test fun feedTabSelectedRedirects() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(pagerTree, selectedTabs = setOf("feed_tab")),
        )

    @Test fun reelsTabSelectedRedirects() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(pagerTree, selectedTabs = setOf("clips_tab")),
        )

    @Test fun exploreTabSelectedRedirects() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(pagerTree, selectedTabs = setOf("search_tab")),
        )

    @Test fun profileTabSelectedRedirects() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(pagerTree, selectedTabs = setOf("profile_tab")),
        )

    @Test fun tabsPresentButNoneDetectablySelectedFailsOpen() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(pagerTree, selectedTabs = emptySet()),
        )

    @Test fun directTabWinsIfSelectionEverReportsTwoTabs() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(pagerTree, selectedTabs = setOf("direct_tab", "feed_tab")),
        )

    // --- Screens without the bottom nav (threads, modals, legacy versions) ---

    @Test fun legacyDmInboxIsAllowed() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(ids("direct_inbox_container", "direct_inbox_recycler_view")),
        )

    @Test fun dmThreadIsAllowed() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(ids("thread_message_list", "row_thread_composer_edittext")),
        )

    @Test fun sharedReelOverDmThreadGoesBack() =
        assertEquals(
            InstagramAction.BACK,
            classifyInstagramScreen(ids("thread_message_list", "clips_viewer_view_pager")),
        )

    @Test fun modalReelsViewerWithoutNavGoesBack() =
        assertEquals(
            InstagramAction.BACK,
            classifyInstagramScreen(ids("clips_viewer_view_pager", "clips_video_container")),
        )

    @Test fun unknownScreenIsAllowed() =
        assertEquals(InstagramAction.ALLOW, classifyInstagramScreen(ids("some_future_id")))

    @Test fun emptyTreeIsAllowed() =
        assertEquals(InstagramAction.ALLOW, classifyInstagramScreen(emptySet()))

    @Test fun ignoresIdsFromOtherNamespacesWithSimilarNames() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(setOf("com.instagram.android:id/my_feed_tab_thing")),
        )
}
