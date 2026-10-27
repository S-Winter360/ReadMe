package com.readme.app.ui.onboarding

/**
 * Defines the planned 5-stage onboarding sequence for ReadMe:
 * 1. [Welcome] — Overview and introduction to ReadMe (Phase 3A)
 * 2. [FloatingBubblePermission] — Overlay/system alert window permission setup (Phase 3B)
 * 3. [ScreenReadingSetup] — Accessibility service and screen OCR setup (Phase 3C)
 * 4. [AutoPageTurnCalibration] — Interactive tap calibration for paged readers (Phase 3D)
 * 5. [Completed] — Onboarding completion and transition to library (Phase 3E)
 */
enum class OnboardingStep {
    Welcome,
    FloatingBubblePermission,
    ScreenReadingSetup,
    AutoPageTurnCalibration,
    Completed
}
