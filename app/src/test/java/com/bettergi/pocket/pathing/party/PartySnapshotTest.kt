package com.bettergi.pocket.pathing.party

import com.bettergi.pocket.pathing.model.PathAction
import com.bettergi.pocket.pathing.model.PathingRoutes
import com.bettergi.pocket.pathing.settings.PathingSettings
import com.bettergi.pocket.recognition.IntRect
import org.junit.Assert.*
import org.junit.Test

class PartySnapshotTest {
    private fun snapshot(vararg names: String) = PartySnapshot(names.mapIndexed { index, name ->
        VisibleAvatar(PartyAvatar(name, name, null, false, false, 0.0, 0.0), IntRect(10, index * 20, 10, 10), 0.99f)
    })

    @Test fun `switch requires the tapped avatar to disappear and one hidden member to appear`() {
        val before = snapshot("七七", "钟离", "迪希雅")
        assertTrue(snapshot("钟离", "纳西妲", "迪希雅").switchedFrom(before, "七七"))
        assertFalse(before.switchedFrom(before, "七七"))
        assertFalse(snapshot("迪希雅", "钟离", "七七").switchedFrom(before, "七七"))
        assertFalse(snapshot("芭芭拉", "纳西妲", "迪希雅").switchedFrom(before, "七七"))
        assertFalse(snapshot("钟离", "迪希雅", "迪希雅").switchedFrom(before, "七七"))
        assertFalse(snapshot("钟离", "迪希雅").switchedFrom(before, "七七"))
    }

    @Test fun `all four elemental routes are accepted and retain target point semantics`() {
        listOf(PathAction.HydroCollect, PathAction.ElectroCollect, PathAction.AnemoCollect, PathAction.PyroCollect).forEach {
            val route = PathingRoutes.parse("""{"info":{"map_name":"Teyvat"},"positions":[{"x":1,"y":2,"type":"path","action":"${it.code}"}]}""")
            assertNull(PathingRoutes.rejectionReason(route))
            assertNotNull(PathingParty.elementFor(it))
            assertTrue(it.supported)
            assertEquals(com.bettergi.pocket.pathing.model.ForcedPointType.Target, it.forcedType)
        }
        assertNull(PathingParty.elementFor(PathAction.Fight))
        assertFalse(PathAction.NahidaCollect.supported)
    }

    @Test fun `guardian interval rejects invalid and subsecond values`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.5).forEach { interval ->
            assertThrows(IllegalArgumentException::class.java) { PathingSettings(guardianIntervalSeconds = interval) }
        }
        assertEquals(0.0, PathingSettings().guardianIntervalSeconds, 0.0)
        assertEquals(12.0, PathingSettings(guardianIntervalSeconds = 12.0).guardianIntervalSeconds, 0.0)
    }
}
