package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.model.PathingResult
import com.bettergi.pocket.pathing.settings.PathingRouteSource
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBatchRunnerTest {
    private val logs = ArrayList<String>()
    private val statuses = ArrayList<String>()
    private val supported = """{"positions":[{"x":1,"y":2,"action":"log_output"}]}"""
    private val unsupported = """{"positions":[{"x":1,"y":2,"action":"fight"}]}"""

    @Test
    fun `unsupported route is logged and next route still executes`() {
        val executed = ArrayList<String>()
        val summary = RouteBatchRunner({}, logs::add, statuses::add).run(
            listOf(source("不支持", unsupported), source("可执行", supported)),
        ) { route ->
            executed += route.waypoints.single().actionCode.orEmpty()
            PathingResult(true, null)
        }
        assertEquals(listOf("log_output"), executed)
        assertEquals(RouteBatchRunner.Summary(1, 1, 0), summary)
        assertTrue(logs.any { "跳过路线 不支持" in it && "战斗" in it })
        assertEquals("2/2 · 可执行", statuses.last())
    }

    @Test
    fun `read parse and execution failures do not abort remaining files`() {
        var attempts = 0
        val summary = RouteBatchRunner({}, logs::add, {}).run(
            listOf(
                PathingRouteSource("无法读取") { error("文件丢失") },
                source("无效JSON", "{"),
                source("执行异常", supported),
                source("执行失败", supported),
                source("正常", supported),
            ),
        ) {
            attempts++
            when (attempts) {
                1 -> error("触控异常")
                2 -> PathingResult(false, "无法定位")
                else -> PathingResult(true, null)
            }
        }
        assertEquals(3, attempts)
        assertEquals(RouteBatchRunner.Summary(1, 0, 4), summary)
        assertTrue(logs.any { "无法读取" in it && "文件丢失" in it })
    }

    @Test
    fun `empty unknown action and unsupported map are skipped before execution`() {
        val summary = RouteBatchRunner({}, logs::add, {}).run(
            listOf(
                source("空路线", "{}"),
                source("未知动作", """{"positions":[{"action":"future_action"}]}"""),
                source("其他地图", """{"info":{"map_name":"Enkanomiya"}}"""),
            ),
        ) { error("不应执行") }
        assertEquals(RouteBatchRunner.Summary(0, 3, 0), summary)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is not swallowed as a failed route`() {
        var readSecond = false
        try {
            RouteBatchRunner({}, logs::add, {}).run(
                listOf(source("第一条", supported), PathingRouteSource("第二条") { readSecond = true; supported }),
            ) { throw CancellationException() }
        } finally {
            assertTrue(!readSecond)
        }
    }

    @Test(expected = InterruptedException::class)
    fun `interrupted input stops the batch`() {
        RouteBatchRunner({}, logs::add, {}).run(listOf(source("第一条", supported))) {
            throw InterruptedException()
        }
    }

    @Test(expected = com.bettergi.pocket.pathing.input.TouchReleaseException::class)
    fun `unconfirmed release stops batch instead of starting next route`() {
        var readSecond = false
        try {
            RouteBatchRunner({}, logs::add, {}).run(
                listOf(source("第一条", supported), PathingRouteSource("第二条") { readSecond = true; supported }),
            ) { throw com.bettergi.pocket.pathing.input.TouchReleaseException() }
        } finally {
            assertTrue(!readSecond)
        }
    }

    @Test
    fun `files are read one at a time immediately before execution`() {
        val events = ArrayList<String>()
        val sources = listOf("一", "二").map { name ->
            PathingRouteSource(name) { events += "读$name"; supported }
        }
        var executed = 0
        RouteBatchRunner({}, {}, {}).run(sources) {
            events += "执行${++executed}"
            PathingResult(true, null)
        }
        assertEquals(listOf("读一", "执行1", "读二", "执行2"), events)
    }

    private fun source(name: String, json: String) = PathingRouteSource(name) { json }
}
