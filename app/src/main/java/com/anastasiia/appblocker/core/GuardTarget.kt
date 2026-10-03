package com.anastasiia.appblocker.core

/**
 * Which guarded app the special-feature guard should classify, and whether it
 * is being viewed through a task preview rather than as the active window.
 *
 * The swipe-up-and-hold gesture (and the Overview that follows it) makes the
 * launcher the active window while the app underneath stays resumed and
 * visible, so a reel keeps playing in the preview card. In that state the
 * guarded app has to be found either among the other visible windows (true
 * mid-gesture) or as a card in the launcher's own Overview tree (true once
 * Overview settles and the app's window is no longer reported). The caller
 * passes only Overview cards it is currently armed for, i.e. apps whose real
 * screen was classified as blocked moments ago.
 */
data class GuardTarget(val pkg: String, val preview: Boolean)

fun resolveGuardTarget(
    activePkg: String?,
    visibleAppPkgs: List<String>,
    isGuarded: (String) -> Boolean,
    previewCardPkgs: List<String> = emptyList(),
): GuardTarget? {
    if (activePkg != null && isGuarded(activePkg)) return GuardTarget(activePkg, preview = false)
    val behind = visibleAppPkgs.firstOrNull { it != activePkg && isGuarded(it) }
        ?: previewCardPkgs.firstOrNull { it != activePkg && isGuarded(it) }
        ?: return null
    return GuardTarget(behind, preview = true)
}
