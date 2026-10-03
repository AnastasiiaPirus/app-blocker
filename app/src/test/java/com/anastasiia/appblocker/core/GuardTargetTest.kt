package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardTargetTest {
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val guarded = setOf(INSTAGRAM_PACKAGE, YOUTUBE_PACKAGE)
    private fun isGuarded(pkg: String) = pkg in guarded

    @Test fun activeGuardedAppIsTheTarget() =
        assertEquals(
            GuardTarget(INSTAGRAM_PACKAGE, preview = false),
            resolveGuardTarget(INSTAGRAM_PACKAGE, listOf(INSTAGRAM_PACKAGE), ::isGuarded),
        )

    @Test fun guardedAppVisibleBehindLauncherIsAPreviewTarget() =
        assertEquals(
            GuardTarget(INSTAGRAM_PACKAGE, preview = true),
            resolveGuardTarget(launcher, listOf(launcher, INSTAGRAM_PACKAGE), ::isGuarded),
        )

    @Test fun activeGuardedAppWinsOverOtherVisibleGuardedApps() =
        assertEquals(
            GuardTarget(YOUTUBE_PACKAGE, preview = false),
            resolveGuardTarget(YOUTUBE_PACKAGE, listOf(INSTAGRAM_PACKAGE, YOUTUBE_PACKAGE), ::isGuarded),
        )

    @Test fun nothingGuardedOnScreenIsNoTarget() =
        assertNull(resolveGuardTarget(launcher, listOf(launcher, "com.whatsapp"), ::isGuarded))

    @Test fun guardedAppNotCurrentlyGuardedIsIgnored() =
        assertNull(resolveGuardTarget(launcher, listOf(launcher, INSTAGRAM_PACKAGE), isGuarded = { false }))

    @Test fun nullActiveWindowStillFindsPreview() =
        assertEquals(
            GuardTarget(YOUTUBE_PACKAGE, preview = true),
            resolveGuardTarget(null, listOf(YOUTUBE_PACKAGE), ::isGuarded),
        )
}

class GuardTargetPreviewCardTest {
    private val launcher = "com.google.android.apps.nexuslauncher"
    private fun isGuarded(pkg: String) = pkg == INSTAGRAM_PACKAGE || pkg == YOUTUBE_PACKAGE

    @Test fun previewCardForGuardedAppIsAPreviewTarget() =
        assertEquals(
            GuardTarget(INSTAGRAM_PACKAGE, preview = true),
            resolveGuardTarget(launcher, listOf(launcher), ::isGuarded, previewCardPkgs = listOf(INSTAGRAM_PACKAGE)),
        )

    @Test fun visibleWindowWinsOverPreviewCard() =
        assertEquals(
            GuardTarget(YOUTUBE_PACKAGE, preview = true),
            resolveGuardTarget(
                launcher, listOf(launcher, YOUTUBE_PACKAGE), ::isGuarded,
                previewCardPkgs = listOf(INSTAGRAM_PACKAGE),
            ),
        )

    @Test fun previewCardForUnguardedAppIsIgnored() =
        assertNull(resolveGuardTarget(launcher, listOf(launcher), ::isGuarded, previewCardPkgs = listOf("com.whatsapp")))
}
