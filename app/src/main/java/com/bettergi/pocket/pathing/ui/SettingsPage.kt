package com.bettergi.pocket.pathing.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.map.MapMatchingMethod
import com.bettergi.pocket.pathing.settings.*
import com.bettergi.pocket.ui.*
import java.math.BigDecimal

enum class CandidateSlot(val title: String, val element: String? = null) {
    Walker("行走位候选"), ShortGuardian("生存位 · 短E候选"), LongGuardian("生存位 · 长E候选"),
    Hydro("水元素采集候选", "Hydro"), Electro("雷元素采集候选", "Electro"),
    Anemo("风元素采集候选", "Anemo"), Pyro("火元素采集候选", "Pyro");
    fun names(config: PathingSettings, repo: PathingSettingsRepository): List<String> = when (this) {
        Walker -> config.mainAvatars; ShortGuardian -> config.guardianShortAvatars; LongGuardian -> config.guardianLongAvatars
        else -> config.elementAvatars[element] ?: repo.defaultElementCandidates(element!!)
    }
    fun defaults(repo: PathingSettingsRepository) = when (this) {
        Walker -> PathingSettings.DEFAULT_WALKERS; ShortGuardian -> PathingSettings.DEFAULT_SHORT_GUARDIANS
        LongGuardian -> PathingSettings.DEFAULT_LONG_GUARDIANS; else -> repo.defaultElementCandidates(element!!)
    }
    fun save(repo: PathingSettingsRepository, names: List<String>) = repo.update { when (this) {
        Walker -> it.copy(mainAvatars = names)
        ShortGuardian -> it.copy(guardianShortAvatars = names, guardianLongAvatars = it.guardianLongAvatars - names.toSet())
        LongGuardian -> it.copy(guardianLongAvatars = names, guardianShortAvatars = it.guardianShortAvatars - names.toSet())
        else -> it.copy(elementAvatars = it.elementAvatars + (element!! to names))
    } }
}

/** 执行和角色各自滚动；所有设置沿用原有存储键，即改即存。 */
class SettingsPage(c: Context, wide: Boolean, private val repo: PathingSettingsRepository,
                   private val pick: (CandidateSlot) -> Unit,
                   private val editNumber: (String, String, (String) -> String?) -> Unit) : LinearLayout(c) {
    private val candidateRows = LinkedHashMap<CandidateSlot, OverlayRow>()
    init {
        orientation = if (wide) HORIZONTAL else VERTICAL
        val execution = OverlayUi.column(c).apply { setPadding(dp(12), dp(4), dp(8), dp(12)) }
        val team = OverlayUi.column(c).apply { setPadding(dp(8), dp(4), dp(12), dp(12)) }
        execution.addView(section("执行"))
        val config = repo.get()
        val matching = OverlayRow(c, "地图匹配", subtitle = "路线未指定时使用")
        matching.trailing.addView(OverlayUi.segments(c, listOf("模板匹配", "SIFT"),
            if (config.mapMatchingMethod == MapMatchingMethod.SIFT) 1 else 0) { index ->
            repo.update { it.copy(mapMatchingMethod = if (index == 0) MapMatchingMethod.TemplateMatch else MapMatchingMethod.SIFT) }
        })
        val gadget = OverlayRow(c, "自动小道具")
        val gadgetStepper = StepperView(c, 100, Int.MAX_VALUE.toLong(), 100, config.useGadgetIntervalMs.toLong(),
            { "${seconds(it)} s" }, { value -> repo.update { it.copy(useGadgetIntervalMs = value.toInt()) } }, { stepper ->
            editNumber("小道具间隔（秒）", seconds(repo.get().useGadgetIntervalMs.toLong())) { value ->
                val ms = parseGadgetInterval(value)
                if (ms == null) "请输入大于 0 的秒数，最多 3 位小数" else {
                    repo.update { it.copy(useGadgetIntervalMs = ms) }; stepper.setValue(ms.toLong()); null
                }
            }
        }, editorMinimum = 1)
        gadget.trailing.addView(gadgetStepper)
        gadget.trailing.addView(SwitchCompat(c, null, androidx.appcompat.R.attr.switchStyle).apply {
            setThumbTintList(androidx.core.content.ContextCompat.getColorStateList(c, R.color.overlay_switch_thumb))
            setTrackTintList(androidx.core.content.ContextCompat.getColorStateList(c, R.color.overlay_switch_track))
            contentDescription = "自动小道具"; isChecked = config.useGadgetEnabled
            setOnCheckedChangeListener { _, enabled -> repo.update { it.copy(useGadgetEnabled = enabled) }; gadgetStepper.isEnabled = enabled }
            layoutParams = LayoutParams(-2, -2).apply { marginStart = dp(8) }
        })
        gadgetStepper.isEnabled = config.useGadgetEnabled
        val guardian = OverlayRow(c, "续盾间隔", subtitle = "自动 = 按角色战技冷却")
        guardian.trailing.addView(StepperView(c, 0, 3_600_000, 1000, (config.guardianIntervalSeconds * 1000).toLong(),
            { if (it == 0L) "自动" else "${seconds(it)} s" }, { value -> repo.update { it.copy(guardianIntervalSeconds = value / 1000.0) } }, { stepper ->
            editNumber("续盾间隔（秒，0 为自动）", repo.get().guardianIntervalSeconds.toString()) { text ->
                val value = text.toDoubleOrNull()
                if (value == null || !value.isFinite() || value != 0.0 && value !in 1.0..3600.0) "请输入 0 或 1～3600 秒"
                else { repo.update { it.copy(guardianIntervalSeconds = value) }; stepper.setValue((value * 1000).toLong()); null }
            }
        }))
        execution.addView(OverlayUi.group(c, matching, gadget, guardian))
        team.addView(section("队伍角色 · 需单人四人队伍，执行中不要手动切人",
            "按已选顺序匹配当前队伍，不在队伍里的候选跳过；生存位先匹配短E组，再匹配长E组。"))
        team.addView(OverlayUi.group(c,
            candidate(CandidateSlot.Walker, "行走位", R.drawable.ic_ui_directions_walk, R.id.pathing_setting_main_avatar),
            candidate(CandidateSlot.ShortGuardian, "生存位·短E", R.drawable.ic_ui_shield, R.id.pathing_setting_guardian_avatar),
            candidate(CandidateSlot.LongGuardian, "生存位·长E", R.drawable.ic_ui_shield, R.id.pathing_setting_guardian_press)))
        team.addView(section("元素采集 · 路线包含对应采集动作时才使用", "默认使用该元素所有具备采集能力的角色；清空即关闭。"))
        val elements = OverlayUi.column(c).apply { setBackgroundResource(R.drawable.bg_pocket_group) }
        listOf(listOf(CandidateSlot.Hydro, CandidateSlot.Electro), listOf(CandidateSlot.Anemo, CandidateSlot.Pyro)).forEach { slots ->
            elements.addView(OverlayUi.row(c).apply { slots.forEach { slot ->
                val label = mapOf(CandidateSlot.Hydro to "水", CandidateSlot.Electro to "雷", CandidateSlot.Anemo to "风", CandidateSlot.Pyro to "火").getValue(slot)
                val icon = mapOf(CandidateSlot.Hydro to R.drawable.ic_ui_water_drop, CandidateSlot.Electro to R.drawable.ic_ui_bolt,
                    CandidateSlot.Anemo to R.drawable.ic_ui_air, CandidateSlot.Pyro to R.drawable.ic_ui_local_fire_department).getValue(slot)
                val row = candidate(slot, label, icon)
                (row.getChildAt(0) as android.widget.ImageView).imageTintList = android.content.res.ColorStateList.valueOf(elementColor(c, slot.element))
                addView(row, LayoutParams(0, -2, 1f))
            } })
        }
        team.addView(elements)
        if (wide) {
            addView(OverlayUi.scroll(c, execution), LayoutParams(0, -1, 1f)); addView(OverlayUi.scroll(c, team), LayoutParams(0, -1, 1f))
        } else {
            addView(OverlayUi.scroll(c, OverlayUi.column(c).apply { addView(execution); addView(team) }), LayoutParams(-1, -1))
        }
        refresh()
    }
    private fun section(label: String, info: String? = null): View = OverlayUi.column(context).apply {
        val description = OverlayUi.text(context, info.orEmpty(), 11f, R.color.pocket_text_secondary).apply { visibility = GONE; setPadding(dp(4), 0, dp(4), dp(8)) }
        addView(OverlayUi.row(context).apply {
            minimumHeight = dp(28)
            addView(OverlayUi.text(context, label, 11f, R.color.pocket_text_secondary, true), LayoutParams(0, -2, 1f))
            if (info != null) addView(OverlayUi.iconButton(context, R.drawable.ic_ui_info, "说明") { description.visibility = if (description.visibility == GONE) VISIBLE else GONE })
        }); addView(description)
    }
    private fun candidate(slot: CandidateSlot, label: String, icon: Int, rowId: Int = View.NO_ID): OverlayRow {
        val row = OverlayRow(context, label, icon, dense = true).apply { id = rowId }
        val value = OverlayUi.text(context, "", 13f).apply { tag = "value"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        // 标签列等宽，值列从相同的位置开始；元素项只显示计数。
        if (slot.element == null) {
            row.titleView.layoutParams = LayoutParams(dp((76 * resources.configuration.fontScale).toInt()), -2)
            row.titleView.maxLines = 1
            (row.titleView.parent as LinearLayout).apply { orientation = HORIZONTAL; addView(value, LayoutParams(0, -2, 1f)) }
        }
        row.trailing.addView(OverlayUi.text(context, "", 12f, R.color.pocket_primary).apply { tag = "count" })
        row.trailing.addView(OverlayUi.icon(context, R.drawable.ic_ui_chevron_right), LayoutParams(dp(16), dp(16)))
        if (slot.element != null) {
            row.setPadding(dp(8), dp(4), dp(8), dp(4))
            (row.getChildAt(0).layoutParams as LayoutParams).apply { width = dp(16); height = dp(16); marginEnd = dp(8) }
            (row.trailing.layoutParams as LayoutParams).marginStart = dp(4)
            row.titleView.apply { maxLines = 1; minWidth = dp(20) }
            row.findViewWithTag<android.widget.TextView>("count").apply { textSize = 11f; maxLines = 1 }
            row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val names = slot.names(repo.get(), repo)
                row.findViewWithTag<android.widget.TextView>("count").text = elementCount(slot, names, row.width)
            }
        }
        row.setOnClickListener { pick(slot) }; candidateRows[slot] = row; return row
    }
    private fun elementCount(slot: CandidateSlot, names: List<String>, width: Int): String = when {
        names.isEmpty() -> "已关闭"
        width > 0 && width < dp((180 * resources.configuration.fontScale).toInt()) -> "${names.size} 人"
        names == slot.defaults(repo) -> "默认 ${names.size} 人"
        else -> "${names.size} 人"
    }
    fun refresh() {
        val config = repo.get()
        candidateRows.forEach { (slot, row) ->
            val names = slot.names(config, repo)
            row.findViewWithTag<android.widget.TextView>("value")?.text = names.joinToString("、").ifEmpty { "已关闭" }
            row.findViewWithTag<android.widget.TextView>("count").text = when {
                names.isEmpty() -> "已关闭"; slot.element == null -> names.size.toString()
                else -> elementCount(slot, names, row.width)
            }
            row.alpha = if (names.isEmpty()) .5f else 1f
            androidx.appcompat.widget.TooltipCompat.setTooltipText(row,
                "${slot.title}：${if (names.isEmpty()) "已关闭" else names.joinToString("、")}")
        }
    }
    companion object {
        fun seconds(ms: Long): String = BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString()
        fun parseGadgetInterval(text: String): Int? = try {
            BigDecimal(text).movePointRight(3).intValueExact().takeIf { it > 0 }
        } catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }
    }
}

fun elementColor(c: Context, element: String?): Int = androidx.core.content.ContextCompat.getColor(c, when (element) {
    "Pyro" -> R.color.pocket_pyro; "Hydro" -> R.color.pocket_hydro; "Anemo" -> R.color.pocket_anemo
    "Electro" -> R.color.pocket_electro; "Cryo" -> R.color.pocket_cryo; "Geo" -> R.color.pocket_geo
    "Dendro" -> R.color.pocket_dendro; else -> R.color.pocket_text_secondary
})

fun elementLabel(element: String?): String = mapOf("Pyro" to "火", "Hydro" to "水", "Anemo" to "风",
    "Electro" to "雷", "Cryo" to "冰", "Geo" to "岩", "Dendro" to "草")[element] ?: "无元素"
