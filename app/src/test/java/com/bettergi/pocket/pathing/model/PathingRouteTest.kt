package com.bettergi.pocket.pathing.model

import com.bettergi.pocket.pathing.PathingTestData
import com.bettergi.pocket.pathing.map.MapMatchingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PathingRouteTest {
    @Test
    fun `resolved route uses the chosen method for every tracked point even after segments were cached`() {
        val route = PathingRoutes.parse(
            """{"positions":[{"x":10,"y":-20,"type":"target"}]}""",
        )
        assertNull(route.segments.single().single().mapMatchMethod)
        for (default in MapMatchingMethod.entries) {
            val resolved = route.withMatchingMethod(default)
            val point = resolved.segments.single().single()
            assertEquals(default.value, point.mapMatchMethod)
            assertEquals(32748.0, point.imageX, 0.001)
            assertEquals(24616.0, point.imageY, 0.001)
        }
        assertNull(route.mapMatchMethod)
    }

    @Test
    fun `parses snake case routes and splits on teleport points`() {
        val route = PathingRoutes.parse(
            """
            {
              "info": {"name": "采集", "map_name": "Teyvat", "map_match_method": "TemplateMatch"},
              "positions": [
                {"x": 0, "y": 0, "type": "path", "move_mode": "walk", "action": "log_output", "action_params": "到了"},
                {"x": 10, "y": -20, "type": "teleport", "move_mode": "walk", "action": "force_tp"},
                {"x": 10, "y": -20, "type": "target", "move_mode": "dash", "action": ""}
              ]
            }
            """.trimIndent(),
        )
        assertNull(PathingRoutes.rejectionReason(route))
        assertEquals(2, route.segments.size)
        assertEquals(1, route.segments[0].size)
        assertEquals(WaypointKind.Teleport, route.segments[1][0].kind)
        assertEquals(PathAction.ForceTp, route.segments[1][0].action)
        val target = route.segments[1][1]
        assertEquals(32768.0 - 2 * 10, target.imageX, 0.001)
        assertEquals(24576.0 - 2 * -20, target.imageY, 0.001)
        assertTrue(PathingRoutes.isTargetPoint(target))
        assertFalse(PathingRoutes.isTargetPoint(route.segments[0][0]))
    }

    @Test
    fun `rejects unsupported actions and other maps`() {
        val fight = PathingRoutes.parse(
            """{"info":{"map_name":"Teyvat"},"positions":[{"x":1,"y":2,"type":"target","action":"fight"}]}""",
        )
        assertEquals("第 1 个点的动作「战斗」不支持，已拒绝整条路线", PathingRoutes.rejectionReason(fight))
        val enka = fight.copy(mapName = "Enkanomiya", waypoints = emptyList())
        assertEquals("只支持提瓦特，当前地图是 Enkanomiya", PathingRoutes.rejectionReason(enka))
    }

    @Test
    fun `forced action type overrides the waypoint type`() {
        val route = PathingRoutes.parse(
            """
            {"info":{"map_name":"Teyvat"},"positions":[
              {"x":1,"y":2,"type":"target","action":"fight"},
              {"x":1,"y":2,"type":"path","action":"hydro_collect"}
            ]}
            """.trimIndent(),
        )
        val points = PathingRoutes.segment(route).single()
        assertFalse(PathingRoutes.isTargetPoint(points[0]))
        assertTrue(PathingRoutes.isTargetPoint(points[1]))
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            PathingTestData.ensureOpenCv()
        }
    }
}
