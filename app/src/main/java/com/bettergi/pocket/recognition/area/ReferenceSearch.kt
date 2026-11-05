package com.bettergi.pocket.recognition.area

import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import com.bettergi.pocket.recognition.RecognitionTypes
import com.bettergi.pocket.recognition.SearchAnchorMode
import com.bettergi.pocket.recognition.SearchOptions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class ReferenceSearchResult(
    val effectiveRoi: IntRect,
    val scale: Double,
    val usedReferenceSearch: Boolean,
    val effectiveTemplateSize: IntSize? = null,
)

/**
 * 参考画布矩形换算到当前画面的结果：`当前坐标 = offset + 参考坐标 × scale`。
 */
data class ReferenceRectMapping(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val rect: IntRect,
)

/**
 * 把 RecognitionObject 的参考画布坐标映射到当前截图上的搜索 ROI。
 * 公式对齐 BetterGI [ImageRegionReferenceSearchHelper]。
 */
object ReferenceSearch {
    const val DEFAULT_EXPAND_PX = 10

    fun hasReferenceSearch(
        roi: IntRect,
        referenceImageSize: IntSize?,
        referenceBoundingBox: IntRect?,
    ): Boolean = roi.isDefault() && referenceImageSize != null && referenceBoundingBox != null

    fun hasPartialReferenceSearch(
        roi: IntRect,
        referenceImageSize: IntSize?,
        referenceBoundingBox: IntRect?,
        searchOptions: SearchOptions?,
    ): Boolean {
        return roi.isDefault() &&
            (referenceImageSize != null || referenceBoundingBox != null || searchOptions != null) &&
            !hasReferenceSearch(roi, referenceImageSize, referenceBoundingBox)
    }

    fun tryGetRegion(
        srcWidth: Int,
        srcHeight: Int,
        roi: IntRect,
        referenceImageSize: IntSize?,
        referenceBoundingBox: IntRect?,
        searchOptions: SearchOptions?,
        canUseReferenceSearch: Boolean,
        recognitionType: RecognitionTypes = RecognitionTypes.None,
    ): ReferenceSearchResult? {
        if (hasPartialReferenceSearch(roi, referenceImageSize, referenceBoundingBox, searchOptions)) {
            return null
        }
        if (!hasReferenceSearch(roi, referenceImageSize, referenceBoundingBox)) {
            return ReferenceSearchResult(roi, 1.0, usedReferenceSearch = false)
        }
        if (!canUseReferenceSearch) {
            return null
        }

        val refSize = referenceImageSize!!
        val bbox = referenceBoundingBox!!
        val options = searchOptions ?: SearchOptions()
        val searchBox = options.referenceSearchBox
        if (refSize.width <= 0 || refSize.height <= 0 ||
            bbox.width <= 0 || bbox.height <= 0 ||
            (searchBox != null && (searchBox.width <= 0 || searchBox.height <= 0)) ||
            options.expandPercent?.isValid == false
        ) {
            return null
        }

        val scale = min(srcWidth / refSize.width.toDouble(), srcHeight / refSize.height.toDouble())
        if (scale <= 0.0) {
            return null
        }

        val (hAnchor, vAnchor) = resolveAnchor(options.anchorMode, bbox, refSize)
        val scaledRefW = refSize.width * scale
        val scaledRefH = refSize.height * scale
        val offsetX = when (hAnchor) {
            HorizontalAnchor.Right -> srcWidth - scaledRefW
            HorizontalAnchor.Center -> (srcWidth - scaledRefW) / 2.0
            HorizontalAnchor.Left -> 0.0
        }
        val offsetY = when (vAnchor) {
            VerticalAnchor.Bottom -> srcHeight - scaledRefH
            VerticalAnchor.Center -> (srcHeight - scaledRefH) / 2.0
            VerticalAnchor.Top -> 0.0
        }

        val transformedBbox = transformReferenceRect(bbox, scale, offsetX, offsetY)
        val effectiveTemplateSize = IntSize(
            width = max(1, round(bbox.width * scale).toInt()),
            height = max(1, round(bbox.height * scale).toInt()),
        )
        val baseSearchRegion = if (searchBox != null) {
            transformReferenceRect(searchBox, scale, offsetX, offsetY)
        } else {
            transformedBbox
        }
        val effectiveRoi = expandAndClampSearchRegion(
            baseSearchRegion,
            srcWidth,
            srcHeight,
            options,
        )
        if (effectiveRoi.width <= 0 || effectiveRoi.height <= 0) {
            return null
        }
        if (recognitionType == RecognitionTypes.TemplateMatch &&
            (effectiveRoi.width < effectiveTemplateSize.width ||
                effectiveRoi.height < effectiveTemplateSize.height)
        ) {
            return null
        }
        return ReferenceSearchResult(
            effectiveRoi = effectiveRoi,
            scale = scale,
            usedReferenceSearch = true,
            effectiveTemplateSize = effectiveTemplateSize,
        )
    }

    /**
     * 把参考画布上的矩形按锚点规则换算到当前画面，不做外扩，也不要求是模板识别。
     * 缩放与锚点偏移的公式与 [tryGetRegion] 相同，供按钮坐标、小地图裁剪等场景使用。
     *
     * @return 参数无效时返回 null
     */
    fun mapReferenceRect(
        srcWidth: Int,
        srcHeight: Int,
        referenceImageSize: IntSize,
        referenceRect: IntRect,
        anchorMode: SearchAnchorMode = SearchAnchorMode.Auto,
    ): ReferenceRectMapping? {
        if (srcWidth <= 0 || srcHeight <= 0 ||
            referenceImageSize.width <= 0 || referenceImageSize.height <= 0 ||
            referenceRect.width <= 0 || referenceRect.height <= 0
        ) {
            return null
        }
        val scale = min(srcWidth / referenceImageSize.width.toDouble(), srcHeight / referenceImageSize.height.toDouble())
        val (hAnchor, vAnchor) = resolveAnchor(anchorMode, referenceRect, referenceImageSize)
        val scaledRefW = referenceImageSize.width * scale
        val scaledRefH = referenceImageSize.height * scale
        val offsetX = when (hAnchor) {
            HorizontalAnchor.Right -> srcWidth - scaledRefW
            HorizontalAnchor.Center -> (srcWidth - scaledRefW) / 2.0
            HorizontalAnchor.Left -> 0.0
        }
        val offsetY = when (vAnchor) {
            VerticalAnchor.Bottom -> srcHeight - scaledRefH
            VerticalAnchor.Center -> (srcHeight - scaledRefH) / 2.0
            VerticalAnchor.Top -> 0.0
        }
        return ReferenceRectMapping(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            rect = transformReferenceRect(referenceRect, scale, offsetX, offsetY),
        )
    }

    fun scaledTemplateSize(bbox: IntRect, scale: Double): IntSize {
        return IntSize(
            width = max(1, round(bbox.width * scale).toInt()),
            height = max(1, round(bbox.height * scale).toInt()),
        )
    }

    internal fun transformReferenceRect(
        referenceRect: IntRect,
        scale: Double,
        offsetX: Double,
        offsetY: Double,
    ): IntRect {
        val left = round(offsetX + referenceRect.left * scale).toInt()
        val top = round(offsetY + referenceRect.top * scale).toInt()
        val right = round(offsetX + referenceRect.right * scale).toInt()
        val bottom = round(offsetY + referenceRect.bottom * scale).toInt()
        return IntRect(
            x = left,
            y = top,
            width = max(1, right - left),
            height = max(1, bottom - top),
        )
    }

    internal fun expandAndClampSearchRegion(
        baseRegion: IntRect,
        imageWidth: Int,
        imageHeight: Int,
        options: SearchOptions,
    ): IntRect {
        val expandLeft: Double
        val expandTop: Double
        val expandRight: Double
        val expandBottom: Double
        val ratio = options.expandPercent
        if (ratio != null) {
            expandLeft = imageWidth * ratio.left
            expandTop = imageHeight * ratio.top
            expandRight = imageWidth * ratio.right
            expandBottom = imageHeight * ratio.bottom
        } else {
            val expandSize = options.expandSize ?: IntSize(DEFAULT_EXPAND_PX, DEFAULT_EXPAND_PX)
            expandLeft = expandSize.width.toDouble()
            expandRight = expandSize.width.toDouble()
            expandTop = expandSize.height.toDouble()
            expandBottom = expandSize.height.toDouble()
        }

        val left = round((baseRegion.left - expandLeft).coerceIn(0.0, imageWidth.toDouble())).toInt()
        val top = round((baseRegion.top - expandTop).coerceIn(0.0, imageHeight.toDouble())).toInt()
        val right = round((baseRegion.right + expandRight).coerceIn(0.0, imageWidth.toDouble())).toInt()
        val bottom = round((baseRegion.bottom + expandBottom).coerceIn(0.0, imageHeight.toDouble())).toInt()
        return IntRect(left, top, max(0, right - left), max(0, bottom - top))
    }

    internal fun resolveAnchor(
        mode: SearchAnchorMode,
        bbox: IntRect,
        refSize: IntSize,
    ): Pair<HorizontalAnchor, VerticalAnchor> {
        return when (mode) {
            SearchAnchorMode.TopLeft -> HorizontalAnchor.Left to VerticalAnchor.Top
            SearchAnchorMode.TopRight -> HorizontalAnchor.Right to VerticalAnchor.Top
            SearchAnchorMode.BottomLeft -> HorizontalAnchor.Left to VerticalAnchor.Bottom
            SearchAnchorMode.BottomRight -> HorizontalAnchor.Right to VerticalAnchor.Bottom
            SearchAnchorMode.Center -> HorizontalAnchor.Center to VerticalAnchor.Center
            SearchAnchorMode.Auto -> resolveAutoAnchor(bbox, refSize)
        }
    }

    private fun resolveAutoAnchor(
        bbox: IntRect,
        refSize: IntSize,
    ): Pair<HorizontalAnchor, VerticalAnchor> {
        val centerX = bbox.x + bbox.width / 2.0
        val centerY = bbox.y + bbox.height / 2.0
        val horizontal = when {
            centerX < refSize.width * 0.4 -> HorizontalAnchor.Left
            centerX > refSize.width * 0.6 -> HorizontalAnchor.Right
            else -> HorizontalAnchor.Center
        }
        val vertical = when {
            centerY < refSize.height * 0.4 -> VerticalAnchor.Top
            centerY > refSize.height * 0.6 -> VerticalAnchor.Bottom
            else -> VerticalAnchor.Center
        }
        return horizontal to vertical
    }

    enum class HorizontalAnchor { Left, Center, Right }
    enum class VerticalAnchor { Top, Center, Bottom }
}
