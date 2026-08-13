package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramRulesTest {
    private fun ids(vararg suffixes: String) =
        suffixes.map { "com.instagram.android:id/$it" }.toSet()

    @Test fun dmInboxIsAllowed() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(ids("direct_inbox_container", "direct_inbox_recycler_view")),
        )

    @Test fun dmThreadIsAllowed() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(ids("thread_message_list", "row_thread_composer_edittext")),
        )

    @Test fun sharedReelOpenedFromDmGoesBack() =
        assertEquals(
            InstagramAction.BACK,
            classifyInstagramScreen(ids("thread_message_list", "clips_viewer_view_pager")),
        )

    @Test fun reelsFeedRedirectsToInbox() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(ids("clips_viewer_view_pager", "clips_tab", "feed_tab")),
        )

    @Test fun homeFeedRedirectsToInbox() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(ids("feed_tab", "search_tab", "clips_tab", "profile_tab")),
        )

    @Test fun exploreRedirectsToInbox() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(ids("search_tab", "feed_tab")),
        )

    @Test fun reelsViewerWithoutAnyContextRedirects() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(ids("clips_viewer_view_pager")),
        )

    @Test fun unknownScreenIsAllowed() =
        assertEquals(InstagramAction.ALLOW, classifyInstagramScreen(ids("some_future_id")))

    @Test fun emptyTreeIsAllowed() =
        assertEquals(InstagramAction.ALLOW, classifyInstagramScreen(emptySet()))

    @Test fun matchesBySuffixNotFullString() =
        assertEquals(
            InstagramAction.REDIRECT_INBOX,
            classifyInstagramScreen(setOf("com.instagram.android:id/feed_tab")),
        )

    @Test fun ignoresIdsFromOtherNamespacesWithSimilarNames() =
        assertEquals(
            InstagramAction.ALLOW,
            classifyInstagramScreen(setOf("com.instagram.android:id/my_feed_tab_thing")),
        )
}
