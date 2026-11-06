package com.bettergi.pocket.pathing.settings

/** 下拉框使用独立草稿；搜索与排序不丢选择，确认前不修改已保存设置。 */
class OrderedAvatarSelection(initial: List<String>) {
    private val selected = initial.distinct().toMutableList()
    val values: List<String> get() = selected.toList()
    fun toggle(name: String) { if (!selected.remove(name)) selected += name }
    fun clear() = selected.clear()
    fun replace(names: List<String>) { selected.clear(); selected.addAll(names.distinct()) }
    fun addAll(names: List<String>) { names.forEach { if (it !in selected) selected += it } }
    fun move(name: String, offset: Int) {
        val index = selected.indexOf(name)
        val next = index + offset
        if (index < 0 || next !in selected.indices) return
        selected.removeAt(index)
        selected.add(next, name)
    }
}
