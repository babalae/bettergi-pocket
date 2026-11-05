package com.bettergi.pocket.pathing.match.template

/**
 * 小地图模板匹配常量。
 * 来源：BGI `Core/Recognition/OpenCv/TemplateMatch/MiniMapMatchConfig.cs`。
 */
object MiniMapMatchConfig {
    /** 原始小地图尺寸（MaskCalculator 从 212 的 ROI 中心裁出的边长）。 */
    const val ORIGINAL_SIZE = 156

    /** 粗匹配时的小地图尺寸。 */
    const val ROUGH_SIZE = 52

    /** 精确匹配时的小地图尺寸。 */
    const val EXACT_SIZE = 260

    const val ROUGH_ZOOM = 5
    const val EXACT_ZOOM = 1
    const val ROUGH_SEARCH_RADIUS = 50
    const val EXACT_SEARCH_RADIUS = 20

    /** 置信度阈值，按 rank 0/1/2 取用。C# 中为 float，比较时会扩展为 double。 */
    val CONFIDENCE_THRESHOLDS = floatArrayOf(0.99f, 0.97f, 0.95f)
}
