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
}
