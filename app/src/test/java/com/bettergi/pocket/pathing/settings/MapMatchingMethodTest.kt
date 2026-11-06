package com.bettergi.pocket.pathing.settings

import com.bettergi.pocket.pathing.map.MapMatchingMethod
import com.bettergi.pocket.pathing.model.PathingRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapMatchingMethodTest {
    @Test
    fun `missing null empty and whitespace route methods follow either setting`() {
        val infos = listOf("{}", """{"map_match_method":null}""", """{"map_match_method":""}""",
            """{"map_match_method":"   "}""")
        for (info in infos) {
            val route = PathingRoutes.parse("""{"info":$info}""")
            assertNull(route.mapMatchMethod)
            for (default in MapMatchingMethod.entries) {
                assertEquals(default.value, route.withMatchingMethod(default).mapMatchMethod)
            }
        }
        val noInfo = PathingRoutes.parse("{}")
        assertEquals("SIFT", noInfo.withMatchingMethod(MapMatchingMethod.SIFT).mapMatchMethod)
    }

    @Test
    fun `explicit method always overrides the setting and is fixed for the task`() {
        for (method in MapMatchingMethod.entries) {
            val route = PathingRoutes.parse("""{"info":{"map_match_method":"${method.value}"}}""")
            for (default in MapMatchingMethod.entries) {
                assertEquals(method.value, route.withMatchingMethod(default).mapMatchMethod)
            }
        }
        val resolved = PathingRoutes.parse("{}").withMatchingMethod(MapMatchingMethod.SIFT)
        assertEquals("SIFT", resolved.withMatchingMethod(MapMatchingMethod.TemplateMatch).mapMatchMethod)
        assertEquals(MapMatchingMethod.TemplateMatch, PathingSettings().mapMatchingMethod)
    }

    @Test
    fun `unknown nonempty values retain BetterGI template fallback`() {
        assertEquals(MapMatchingMethod.TemplateMatch, MapMatchingMethod.resolve("unknown", MapMatchingMethod.SIFT))
    }

    @Test
    fun `catalog displays effective algorithms and their source without changing selection`() {
        val catalog = RouteCatalog.parse(
            "采集/默认.json\n采集/空值.json\n采集/空白.json\n采集/SIFT.json\n采集/模板.json\n采集/损坏.json\n采集/缺失.json",
            """{
                "采集/默认.json":{},
                "采集/空值.json":{"map_match_method":null},
                "采集/空白.json":{"map_match_method":"  "},
                "采集/SIFT.json":{"map_match_method":"SIFT"},
                "采集/模板.json":{"map_match_method":"TemplateMatch"},
                "采集/损坏.json":null
            }""".trimIndent(),
        )
        for (default in MapMatchingMethod.entries) {
            for (name in listOf("默认", "空值", "空白")) {
                assertEquals("${default.label}（跟随设置）", catalog.node("采集/$name.json")!!.matchingDescription(default))
            }
            assertEquals("SIFT（路线指定）", catalog.node("采集/SIFT.json")!!.matchingDescription(default))
            assertEquals("模板匹配（路线指定）", catalog.node("采集/模板.json")!!.matchingDescription(default))
            assertEquals("匹配方式读取失败", catalog.node("采集/损坏.json")!!.matchingDescription(default))
            assertEquals("匹配方式读取失败", catalog.node("采集/缺失.json")!!.matchingDescription(default))
        }
        assertEquals(7, catalog.resolve(setOf("采集")).size)
        assertEquals("SIFT", catalog.browse("采集", "SIFT").rows.single().node.mapMatchMethod)
        assertEquals("TemplateMatch", catalog.rows(setOf("采集"), "模板").rows.single().node.mapMatchMethod)
    }
}
