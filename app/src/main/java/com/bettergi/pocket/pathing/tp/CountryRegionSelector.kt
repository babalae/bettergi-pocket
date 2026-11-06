package com.bettergi.pocket.pathing.tp

import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrFactory
import org.opencv.core.Mat

/** 地区名单为两列，左列从屏幕约 58% 处开始，裁图必须保留首字及文字周围的留白。 */
internal object CountryRegionSelector {
    fun find(
        frame: Mat,
        country: String,
        ocr: IOcrService = OcrFactory.default,
        onRecognized: (List<String>) -> Unit = {},
    ): Pair<Int, Int>? {
        if (frame.empty() || country.isBlank()) return null
        val x0 = (frame.cols() * 0.56).toInt()
        val roi = frame.submat(0, frame.rows(), x0, frame.cols())
        val result = try {
            ocr.recognize(roi)
        } finally {
            roi.release()
        }
        onRecognized(result.regions.map { it.text })
        val target = normalize(country)
        // 高亮项旁边的铁砧图标可能被 OCR 合进同一行，保留完整地区名即可。
        val hit = result.regions.firstOrNull { normalize(it.text).contains(target) } ?: return null
        return x0 + hit.rect.centerX to hit.rect.centerY
    }

    private fun normalize(text: String): String =
        TpCatalog.normalizeAreaName(text).filterNot { it.isWhitespace() }
}
