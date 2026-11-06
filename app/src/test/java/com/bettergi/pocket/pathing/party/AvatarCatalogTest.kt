package com.bettergi.pocket.pathing.party

import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AvatarCatalogTest {
    private val folder = listOf(File("src/main/assets/recognition/Party"), File("app/src/main/assets/recognition/Party"))
        .first { it.isDirectory }
    private val json = File(folder, "combat_avatar.json").readText(Charsets.UTF_8)
    private val catalog = AvatarCatalog.parse(json, File(folder, "avatar_labels.json").readText(Charsets.UTF_8))

    @Test fun `every PC configuration row maps its English name to its configured Chinese name`() {
        val rows = JSONArray(json)
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val mapped = catalog.byLabel(row.getString("nameEn"))!!
            assertEquals(row.getString("name"), mapped.name)
            assertEquals(row.optString("elementalType").takeIf { it.isNotBlank() }, mapped.element)
            assertEquals(row.optBoolean("normalAttack"), mapped.normalAttack)
            assertEquals(row.optBoolean("elementalSkill"), mapped.elementalSkill)
            assertEquals(row.optDouble("skillCD", 0.0), mapped.skillCd, 0.0)
            assertEquals(row.optDouble("skillHoldCD", 0.0), mapped.skillHoldCd, 0.0)
        }
    }

    @Test fun `costume labels reuse PC base character mapping`() {
        assertEquals("琴", catalog.byLabel("QinCostumeSea")!!.name)
        assertEquals("芭芭拉", catalog.byLabel("BarbaraCostumeSummertime")!!.name)
        assertEquals("钟离", catalog.byLabel("Zhongli")!!.name)
        assertEquals("桑多涅", catalog.byLabel("Marionette")!!.name)
        assertEquals("奥黛塔", catalog.byLabel("Odette")!!.name)
        assertEquals(catalog.byName("七七"), catalog.byLabel("Qiqi"))
    }

    @Test fun `NPC model classes remain unmapped and model output indices retain metadata order`() {
        assertEquals(162, catalog.labels.size)
        assertEquals("Aino", catalog.labels[0])
        assertEquals("Zhongli", catalog.labels[160])
        assertNull(catalog.byLabel("Paimon"))
        assertNull(catalog.byLabel("Mosasaurus"))
    }
}
