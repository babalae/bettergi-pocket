package com.bettergi.pocket.pathing.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCatalogTest {
    private val catalog = RouteCatalog.parse(
        """
        矿物/a/1.json
        矿物/b/2.json
        食材/禽肉/C.json
        # comment

        readme.txt
        矿物/a/1.json
        """.trimIndent(),
    )

    @Test
    fun `index becomes a folder tree and ignores junk`() {
        assertEquals(setOf("矿物", "食材"), catalog.roots.map { it.name }.toSet())
        val mineral = catalog.roots.first { it.name == "矿物" }
        assertFalse(mineral.file)
        assertEquals(listOf("a", "b"), mineral.children.map { it.name })
        val file = mineral.children.first { it.name == "a" }.children.single()
        assertTrue(file.file)
        assertEquals("1", file.name)
        assertEquals("矿物/a/1.json", file.relativePath)
    }

    @Test
    fun `collapsed tree only shows the top folders`() {
        val listing = catalog.rows(emptySet(), "")
        assertFalse(listing.truncated)
        assertEquals(catalog.roots.map { it.name }, listing.rows.map { it.node.name })
        assertTrue(listing.rows.all { it.depth == 0 && !it.expanded })
    }

    @Test
    fun `expanded folders reveal their children`() {
        val listing = catalog.rows(setOf("矿物", "矿物/a"), "")
        val file = listing.rows.first { it.node.relativePath == "矿物/a/1.json" }
        assertEquals(2, file.depth)
        assertTrue(listing.rows.first { it.node.relativePath == "矿物" }.expanded)
    }

    @Test
    fun `search lists matching files with their folder`() {
        val listing = catalog.rows(emptySet(), "2")
        assertEquals(1, listing.rows.size)
        assertEquals("矿物/b/2.json", listing.rows[0].node.relativePath)
        assertEquals("矿物/b", listing.rows[0].breadcrumb)
        assertEquals(0, listing.rows[0].depth)
    }

    @Test
    fun `search stops at the limit`() {
        val listing = catalog.rows(emptySet(), "json", limit = 1)
        assertEquals(1, listing.rows.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun `directory selection includes descendants without duplicate or prefix collisions`() {
        val routes = RouteCatalog.parse("采集/a/1.json\n采集/a/sub/2.json\n采集/ab/3.json")
        val selected = routes.resolve(setOf("采集/a", "采集/a/1.json"))
        assertEquals(listOf("采集/a/sub/2.json", "采集/a/1.json"), selected.map { it.relativePath })
        assertEquals(3, routes.resolve(setOf("采集")).size)
        assertTrue(routes.resolve(setOf("不存在")).isEmpty())
    }

    @Test
    fun `browser searches folders and files and stays in current directory`() {
        val routes = RouteCatalog.parse("食材/甜甜花/1.json\n食材/薄荷/2.json\n矿物/甜甜花/3.json")
        val result = routes.browse("食材", "甜甜花")
        assertEquals(listOf("食材/甜甜花"), result.rows.map { it.node.relativePath })
        assertEquals(1, result.rows.single().node.routePaths.size)
        assertEquals("食材/薄荷/2.json", routes.browse("食材", "2").rows.single().node.relativePath)
    }

    @Test
    fun `browser supports pinyin index and multiple keywords`() {
        val routes = RouteCatalog.parse("食材/甜甜花/蒙德1.json") { "tiantianhua mengde tth md" }
        assertEquals(1, routes.browse("", "tth md").rows.size)
        assertTrue(routes.browse("", "tth liyue").rows.isEmpty())
    }

    @Test
    fun `catalog ignores paths escaping asset root`() {
        val routes = RouteCatalog.parse("../坏.json\n/root.json\n./bad.json\n正常/好.json")
        assertEquals(listOf("正常"), routes.roots.map { it.name })
    }
}
