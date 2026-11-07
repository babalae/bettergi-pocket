package com.bettergi.pocket.pathing.settings

import com.bettergi.pocket.pathing.ui.SettingsPage
import org.junit.Assert.*
import org.junit.Test

class OverlayIntervalTest {
    @Test fun `gadget editor preserves millisecond precision and rejects overflow`() {
        assertEquals(1, SettingsPage.parseGadgetInterval("0.001"))
        assertEquals(1234, SettingsPage.parseGadgetInterval("1.234"))
        assertEquals(Int.MAX_VALUE, SettingsPage.parseGadgetInterval("2147483.647"))
        listOf("0", "-1", "0.0001", "2147483.648", "", "abc").forEach { assertNull(it, SettingsPage.parseGadgetInterval(it)) }
        assertEquals("0.001", SettingsPage.seconds(1))
    }
}
