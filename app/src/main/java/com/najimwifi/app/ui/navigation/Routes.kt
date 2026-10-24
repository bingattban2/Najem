package com.najimwifi.app.ui.navigation

import androidx.annotation.StringRes
import com.najimwifi.app.R

enum class RootDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val iconGlyph: String
) {
    HOME(
        route = "home",
        labelRes = R.string.nav_home,
        iconGlyph = "⌂"
    ),

    MANAGEMENT(
        route = "management",
        labelRes = R.string.nav_management,
        iconGlyph = "▦"
    ),

    SETTINGS(
        route = "settings",
        labelRes = R.string.nav_settings,
        iconGlyph = "⚙"
    )
}