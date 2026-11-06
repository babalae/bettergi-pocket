package com.bettergi.pocket.pathing.settings

import org.junit.Assert.*
import org.junit.Test

class OrderedAvatarSelectionTest {
    @Test fun `selecting and filtering preserve priority without duplicates`() {
        val selection = OrderedAvatarSelection(listOf("七七", "钟离", "七七"))
        selection.addAll(listOf("钟离", "迪希雅"))
        assertEquals(listOf("七七", "钟离", "迪希雅"), selection.values)
        selection.move("迪希雅", -1)
        assertEquals(listOf("七七", "迪希雅", "钟离"), selection.values)
        selection.toggle("七七")
        assertEquals(listOf("迪希雅", "钟离"), selection.values)
        selection.move("迪希雅", -1)
        assertEquals(listOf("迪希雅", "钟离"), selection.values)
        selection.clear()
        assertTrue(selection.values.isEmpty())
        selection.replace(PathingSettings.DEFAULT_SHORT_GUARDIANS)
        assertEquals(PathingSettings.DEFAULT_SHORT_GUARDIANS, selection.values)
    }

    @Test fun `legacy auto single and disabled choices migrate without changing meaning`() {
        val defaults = PathingSettings.DEFAULT_WALKERS
        assertEquals(defaults, CandidatePreferences.fromLegacy(null, defaults))
        assertEquals(defaults, CandidatePreferences.fromLegacy("自动", defaults))
        assertEquals(listOf("钟离"), CandidatePreferences.fromLegacy("钟离", defaults))
        assertEquals(emptyList<String>(), CandidatePreferences.fromLegacy("", defaults))
        assertEquals(listOf("钟离", "七七"), CandidatePreferences.decode("""["钟离","七七","钟离",""]"""))
        assertEquals(emptyList<String>(), CandidatePreferences.decode("[]"))
        assertNull(CandidatePreferences.decode("broken"))
    }
}
