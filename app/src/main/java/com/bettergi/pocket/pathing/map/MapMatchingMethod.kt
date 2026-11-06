package com.bettergi.pocket.pathing.map

/** 路线指定优先；未填写、空字符串和纯空白跟随本次任务的设置。 */
enum class MapMatchingMethod(val value: String, val label: String) {
    TemplateMatch("TemplateMatch", "模板匹配"),
    SIFT("SIFT", "SIFT");

    companion object {
        fun resolve(routeMethod: String?, default: MapMatchingMethod = TemplateMatch): MapMatchingMethod =
            when {
                routeMethod.isNullOrBlank() -> default
                routeMethod == SIFT.value -> SIFT
                else -> TemplateMatch // 与 BGI 一致，其他非空值走模板匹配。
            }
    }
}
