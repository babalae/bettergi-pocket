package com.bettergi.pocket.pathing.map

import com.bettergi.pocket.pathing.PathingTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat

class GrayMapCacheTest {
    private fun layer(id: String) = BaseMapLayerByTemplateMatch("", id, "", 1f, 0, 0f, 0f, false)

    @Test
    fun `evicts least recently used layer beyond budget`() {
        val cache = GrayMapCache(maxBytes = 250)
        val a = layer("a")
        val b = layer("b")
        val c = layer("c")
        var loads = 0
        val loader = { loads++; Mat(10, 10, CvType.CV_8UC1) }

        val matA = cache.get(a, loader)
        cache.get(b, loader)
        assertSame(matA, cache.get(a, loader)) // a 变为最近使用
        cache.get(c, loader) // 超出 250 字节，淘汰最久未用的 b
        assertEquals(3, loads)
        assertEquals(300L - 100L, cache.loadedBytes())

        cache.get(a, loader)
        assertEquals(3, loads)
        cache.get(b, loader) // b 已被淘汰，需要重新加载
        assertEquals(4, loads)
        cache.clear()
        assertEquals(0L, cache.loadedBytes())
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = PathingTestData.ensureOpenCv()
    }
}
