package com.kronokeep.app.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test fun `system follows the phone`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemIsDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemIsDark = false))
    }

    @Test fun `explicit choices ignore the phone`() {
        assertFalse(ThemeMode.LIGHT.isDark(systemIsDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemIsDark = false))
    }

    @Test fun `stored names round-trip`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStorage(it.name)) }
    }

    @Test fun `missing or unknown stored values fall back to system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("SEPIA"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("dark"))
    }
}
