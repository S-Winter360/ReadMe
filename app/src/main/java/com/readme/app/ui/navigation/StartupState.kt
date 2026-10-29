package com.readme.app.ui.navigation

/**
 * Architectural tri-state model for app launch and navigation lifecycle.
 *
 * Prevents the onboarding screen from flashing during subsequent app launches:
 * - [Loading]: Initial state while persistent preferences are being asynchronously loaded from DataStore.
 *   During this state, a neutral branded launch screen is rendered, completely preventing [Onboarding]
 *   composition or frame rendering.
 * - [NeedsOnboarding]: Dispatched only when preferences have resolved and confirmed that onboarding
 *   has not yet been completed (first install / cleared data).
 * - [Ready]: Dispatched when preferences have resolved that onboarding is complete, allowing direct
 *   composition of the main application.
 */
sealed interface StartupState {
    data object Loading : StartupState
    data object NeedsOnboarding : StartupState
    data object Ready : StartupState
}
