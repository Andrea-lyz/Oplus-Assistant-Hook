package com.xdreamllc.oplus

/**
 * Central configuration constants for the module.
 * SharedPreferences name: "oplus_hook_config"
 */
object Config {
    const val PREFS_NAME = "oplus_hook_config"

    // === Power button -> Assistant replacement ===
    /** Key controlling the power-button long-press action */
    const val KEY_POWER_MODE = "power_mode"
    /** Launch Gemini (via voice command intent) */
    const val POWER_MODE_GEMINI = 0
    /** Launch Circle to Search (via contextual search service) */
    const val POWER_MODE_CIRCLE = 1
    /** Disabled – let the original Xiaobu assistant handle it */
    const val POWER_MODE_NONE = -1

    // === Nav bar gesture indicator long-press -> Circle to Search ===
    /** Key controlling whether the gesture-bar long-press hook is enabled */
    const val KEY_GESTURE_BAR_ENABLED = "gesture_bar_enabled"

    // === Misc ===
    const val KEY_LOG_ENABLED = "log_enabled"

    // === Package names ===
    const val PKG_GEMINI = "com.google.android.apps.bard"
    const val PKG_GOOGLE = "com.google.android.googlequicksearchbox"
}
