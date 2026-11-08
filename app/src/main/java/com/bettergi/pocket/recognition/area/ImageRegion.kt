package com.bettergi.pocket.recognition.area

import android.util.Log
import com.bettergi.pocket.recognition.ColorConversion
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.OcrText
import com.bettergi.pocket.recognition.RecognitionObject
import com.bettergi.pocket.recognition.RecognitionTypes
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.opencv.MatchTemplateHelper
import com.bettergi.pocket.recognition.opencv.MatOps
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.round

open class ImageRegion(
    srcMat: Mat,
    x: Int,
    y: Int,
    prev: Region? = null,
    prevConverter: NodeConverter? = null,
    private val ownsMat: Boolean = true,
    protected val ocrService: IOcrService = OcrFactory.default,
) : Region(x, y, srcMat.cols(), srcMat.rows(), prev, prevConverter) {
    var srcMat: Mat = srcMat
        private set

    private var cacheGreyMat: Mat? = null
    private var released: Boolean = false

    val cacheGreyMatSafe: Mat
        get() {
            val cached = cacheGreyMat
            if (cached != null) return cached
            val gray = MatOps.bgrToGray(srcMat)
            cacheGreyMat = gray
            return gray
        }

    fun deriveCrop(x: Int, y: Int, w: Int, h: Int): ImageRegion {
        val rect = IntRect(x, y, w, h).clampTo(srcMat.cols(), srcMat.rows())
        if (rect.width <= 0 || rect.height <= 0) {
            throw IllegalArgumentException(
                "DeriveCrop 裁剪区域无效: ($x,$y,$w,$h)，图像大小: ${srcMat.cols()}x${srcMat.rows()}",
            )
        }
        return ImageRegion(
            srcMat = MatOps.roiView(srcMat, rect),
            x = rect.x,
            y = rect.y,
            prev = this,
            prevConverter = TranslationConverter(rect.x, rect.y),
            ownsMat = true,
            ocrService = ocrService,
        )
    }

    fun deriveCrop(rect: IntRect): ImageRegion = deriveCrop(rect.x, rect.y, rect.width, rect.height)

    fun find(ro: RecognitionObject): Region {
        return when (ro.recognitionType) {
            RecognitionTypes.TemplateMatch -> findTemplate(ro)
            RecognitionTypes.OcrMatch -> findOcrMatch(ro)
            RecognitionTypes.Ocr, RecognitionTypes.ColorRangeAndOcr -> findOcr(ro)
            else -> throw IllegalArgumentException("ImageRegion不支持的识别类型${ro.recognitionType}")
        }
    }

    fun findMulti(ro: RecognitionObject): List<Region> {
        return when (ro.recognitionType) {
            RecognitionTypes.TemplateMatch -> findTemplateMulti(ro)
            RecognitionTypes.Ocr -> findOcrMulti(ro)
            else -> throw IllegalArgumentException("RectArea多目标识别不支持的识别类型${ro.recognitionType}")
        }
    }

    fun exists(ro: RecognitionObject): Boolean = find(ro).isExist()

    private fun findTemplate(ro: RecognitionObject): Region {
        val template = requiredTemplate(ro)

        val search = resolveSearch(ro) ?: return Region()
        var ownedSource: Mat? = null
        var ownedRoiView: Mat? = null
        var ownedTemplate: Mat? = null
        var ownedMask: Mat? = null
        try {
            val source = templateMatchSource(ro).also { if (it !== cacheGreyMatSafe && it !== srcMat) ownedSource = it }
            var roi = source
            if (!search.effectiveRoi.isDefault()) {
                if (!isRoiInside(source, search.effectiveRoi)) {
                    Log.e(
                        TAG,
                        "在图像${source.cols()}x${source.rows()}中查找模板,名称：${ro.name}," +
                            "ROI位置${search.effectiveRoi.x}x${search.effectiveRoi.y}," +
                            "区域${search.effectiveRoi.width}x${search.effectiveRoi.height},边界溢出！",
                    )
                }
                ownedRoiView = MatOps.roiView(source, search.effectiveRoi)
                roi = ownedRoiView
            }

            val effectiveTemplate = effectiveTemplate(ro, template, search).also {
                if (it !== template) ownedTemplate = it
            }
            val effectiveMask = effectiveMask(ro.maskMat, effectiveTemplate).also {
                if (it != null && it !== ro.maskMat) ownedMask = it
            }

            if (roi.cols() < effectiveTemplate.cols() || roi.rows() < effectiveTemplate.rows()) {
                return Region()
            }

            val match = MatchTemplateHelper.findBestMatch(
                roi,
                effectiveTemplate,
                ro.templateMatchMode,
                effectiveMask,
                ro.threshold,
            ) ?: return Region()

            val hit = derive(
                match.x + search.effectiveRoi.x,
                match.y + search.effectiveRoi.y,
                effectiveTemplate.cols(),
                effectiveTemplate.rows(),
            )
            hit.matchScore = match.score
            return hit
        } finally {
            ownedRoiView?.release()
            ownedSource?.release()
            ownedTemplate?.release()
            ownedMask?.release()
        }
    }

    private fun findTemplateMulti(ro: RecognitionObject): List<Region> {
        val template = requiredTemplate(ro)

        val search = resolveSearch(ro) ?: return emptyList()
        var ownedSource: Mat? = null
        var ownedRoiView: Mat? = null
        var ownedTemplate: Mat? = null
        var ownedMask: Mat? = null
        try {
            val source = templateMatchSource(ro).also { if (it !== cacheGreyMatSafe && it !== srcMat) ownedSource = it }
            var roi = source
            if (!search.effectiveRoi.isDefault()) {
                ownedRoiView = MatOps.roiView(source, search.effectiveRoi)
                roi = ownedRoiView
            }

            val effectiveTemplate = effectiveTemplate(ro, template, search).also {
                if (it !== template) ownedTemplate = it
            }
            val effectiveMask = effectiveMask(ro.maskMat, effectiveTemplate).also {
                if (it != null && it !== ro.maskMat) ownedMask = it
            }

            if (roi.cols() < effectiveTemplate.cols() || roi.rows() < effectiveTemplate.rows()) {
                return emptyList()
            }

            return MatchTemplateHelper.findMatches(
                roi,
                effectiveTemplate,
                ro.templateMatchMode,
                effectiveMask,
                ro.threshold,
                ro.maxMatchCount,
            ).map { match ->
                derive(
                    match.x + search.effectiveRoi.x,
                    match.y + search.effectiveRoi.y,
                    effectiveTemplate.cols(),
                    effectiveTemplate.rows(),
                ).also { it.matchScore = match.score }
            }
        } finally {
            ownedRoiView?.release()
            ownedSource?.release()
            ownedTemplate?.release()
            ownedMask?.release()
        }
    }

    private fun findOcrMatch(ro: RecognitionObject): Region {
        if (ro.allContainMatchText.isEmpty() && ro.oneContainMatchText.isEmpty() && ro.regexMatchText.isEmpty()) {
            throw IllegalArgumentException("[OCR]识别对象${ro.name}的匹配文本不能全为空")
        }
        val search = resolveSearch(ro) ?: return Region()
        val ownedRoi = if (!search.effectiveRoi.isDefault()) MatOps.roiView(srcMat, search.effectiveRoi) else null
        try {
            val roi = ownedRoi ?: srcMat
            val result = ocrService.recognize(roi)
            val text = OcrText.normalize(result.text, ro.replaceDictionary)
            return if (OcrText.matches(text, ro.allContainMatchText, ro.oneContainMatchText, ro.regexMatchText)) {
                derive(search.effectiveRoi.takeUnless { it.isDefault() } ?: IntRect(0, 0, width, height))
            } else {
                Region()
            }
        } finally {
            ownedRoi?.release()
        }
    }

    private fun findOcr(ro: RecognitionObject): Region {
        val search = resolveSearch(ro) ?: return Region()
        val ownedRoi = if (!search.effectiveRoi.isDefault()) MatOps.roiView(srcMat, search.effectiveRoi) else null
        var colorConverted: Mat? = null
        var colorMasked: Mat? = null
        try {
            var roi = ownedRoi ?: srcMat
            if (ro.recognitionType == RecognitionTypes.ColorRangeAndOcr) {
                val converted = if (ro.colorConversion == ColorConversion.None) {
                    roi
                } else {
                    MatOps.convertColor(roi, ro.colorConversion).also { colorConverted = it }
                }
                colorMasked = MatOps.inRange(converted, ro.lowerColor, ro.upperColor)
                roi = colorMasked
            }
            val result = ocrService.recognize(roi)
            val text = OcrText.normalize(result.text, ro.replaceDictionary)
            if (text.isEmpty()) {
                return Region()
            }
            val hit = derive(search.effectiveRoi.takeUnless { it.isDefault() } ?: IntRect(0, 0, width, height))
            hit.text = text
            return hit
        } finally {
            ownedRoi?.release()
            colorConverted?.release()
            colorMasked?.release()
        }
    }

    private fun findOcrMulti(ro: RecognitionObject): List<Region> {
        val search = resolveSearch(ro) ?: return emptyList()
        val ownedRoi = if (!search.effectiveRoi.isDefault()) MatOps.roiView(srcMat, search.effectiveRoi) else null
        try {
            val roi = ownedRoi ?: srcMat
            val result = ocrService.recognize(roi)
            val offsetX = if (search.effectiveRoi.isDefault()) 0 else search.effectiveRoi.x
            val offsetY = if (search.effectiveRoi.isDefault()) 0 else search.effectiveRoi.y
            return result.regions.mapNotNull { ocrRegion ->
                val clamped = ocrRegion.rect.clampTo(roi.cols(), roi.rows())
                if (clamped.isEmpty()) {
                    null
                } else {
                    derive(clamped.offset(offsetX, offsetY)).also {
                        it.text = OcrText.applyReplacements(ocrRegion.text, ro.replaceDictionary)
                    }
                }
            }
        } finally {
            ownedRoi?.release()
        }
    }

    private fun resolveSearch(ro: RecognitionObject): ReferenceSearchResult? {
        return ReferenceSearch.tryGetRegion(
            srcWidth = srcMat.cols(),
            srcHeight = srcMat.rows(),
            roi = ro.regionOfInterest,
            referenceImageSize = ro.referenceImageSize,
            referenceBoundingBox = ro.referenceBoundingBox,
            searchOptions = ro.searchOptions,
            canUseReferenceSearch = canUseReferenceSearch(),
            recognitionType = ro.recognitionType,
            uiGeometry = GameUiCalibration.current,
        )
    }

    private fun canUseReferenceSearch(): Boolean {
        return this is GameCaptureRegion ||
            (prev is GameCaptureRegion && prevConverter is ScaleConverter)
    }

    private fun requiredTemplate(ro: RecognitionObject): Mat {
        val template = if (ro.use3Channels) ro.templateImageMat else ro.templateImageGreyMat
        return template
            ?: throw IllegalArgumentException("[TemplateMatch]识别对象${ro.name}的模板图片不能为null")
    }

    private fun templateMatchSource(ro: RecognitionObject): Mat {
        if (ro.use3Channels) {
            return srcMat
        }
        if (ro.useBinaryMatch) {
            return MatOps.binary(cacheGreyMatSafe, ro.binaryThreshold)
        }
        return cacheGreyMatSafe
    }

    private fun effectiveTemplate(
        ro: RecognitionObject,
        template: Mat,
        search: ReferenceSearchResult,
    ): Mat {
        if (!search.usedReferenceSearch) {
            return template
        }
        val target = search.effectiveTemplateSize
            ?: ro.referenceBoundingBox?.let { ReferenceSearch.scaledTemplateSize(it, search.scale) }
            ?: return template
        return MatOps.resize(template, target)
    }

    private fun effectiveMask(mask: Mat?, effectiveTemplate: Mat): Mat? {
        if (mask == null || (mask.cols() == effectiveTemplate.cols() && mask.rows() == effectiveTemplate.rows())) {
            return mask
        }
        return MatOps.resize(
            mask,
            com.bettergi.pocket.recognition.IntSize(effectiveTemplate.cols(), effectiveTemplate.rows()),
            Imgproc.INTER_NEAREST,
        )
    }

    private fun isRoiInside(src: Mat, roi: IntRect): Boolean {
        return roi.x >= 0 && roi.y >= 0 &&
            roi.width >= 0 && roi.height >= 0 &&
            roi.x + roi.width <= src.cols() &&
            roi.y + roi.height <= src.rows()
    }

    internal fun releaseOwnedMats() {
        if (released) return
        released = true
        cacheGreyMat?.release()
        cacheGreyMat = null
        if (ownsMat) {
            srcMat.release()
        }
    }

    override fun close() {
        releaseOwnedMats()
        super.close()
    }

    private companion object {
        private const val TAG = "BetterGI.Region"
    }
}

class GameCaptureRegion(
    srcMat: Mat,
    x: Int,
    y: Int,
    prev: Region? = null,
    prevConverter: NodeConverter? = null,
    ocrService: IOcrService = OcrFactory.default,
) : ImageRegion(srcMat, x, y, prev, prevConverter, ownsMat = true, ocrService = ocrService) {

    /**
     * 捕获宽大于 1920 时缩到 1080P 宽，坐标通过 [ScaleConverter] 回到原生分辨率。
     */
    fun deriveTo1080P(): ImageRegion {
        if (width <= 1920) {
            return this
        }
        val scale = width / 1920.0
        val resized = Mat()
        Imgproc.resize(srcMat, resized, Size(1920.0, height / scale))
        releaseOwnedMats()
        return ImageRegion(
            srcMat = resized,
            x = 0,
            y = 0,
            prev = this,
            prevConverter = ScaleConverter(scale),
            ownsMat = true,
            ocrService = ocrService,
        )
    }

    fun to1080PPos(nativeX: Double, nativeY: Double): Pair<Int, Int> {
        val scale = if (width > 1920) width / 1920.0 else 1.0
        return round(nativeX / scale).toInt() to round(nativeY / scale).toInt()
    }
}
