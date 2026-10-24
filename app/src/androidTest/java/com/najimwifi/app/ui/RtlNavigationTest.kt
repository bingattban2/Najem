package com.najimwifi.app.ui

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.ui.navigation.RootDestination
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the navigation/RTL configuration contract, not Compose touch interactions. */
@RunWith(AndroidJUnit4::class)
class RtlNavigationTest {
    @Test fun onlyThreeTabsExistInApprovedOrderAndHaveArabicLabels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roots = RootDestination.entries
        assertEquals(listOf("home", "management", "settings"), roots.map { it.route })
        assertEquals(3, roots.size)
        roots.forEach { root ->
            val label = context.getString(root.labelRes)
            assertTrue(label.isNotBlank())
            assertTrue(label.any { it in '\u0621'..'\u064A' })
        }
    }

    @Test fun arabicLocaleResolvesRtl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar-YE"))
            setLayoutDirection(Locale.forLanguageTag("ar-YE"))
        }
        assertEquals(View.LAYOUT_DIRECTION_RTL, configuration.layoutDirection)
    }
}
