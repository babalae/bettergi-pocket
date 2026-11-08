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
        val hit = result.regions.firstOrNull {
            val text = normalize(it.text)
            if (!text.contains(target)) false else {
                // 顶部提示如「游逸旅闻：蒙德 璃月 稻妻」也含地名，不能当作地区按钮。
                // 保留高亮项旁图标被识别为「工」的兼容行为，其他汉字必须属于完整地名。
                val rest = text.replace(target, "")
                rest.all { char -> char == '工' || char !in '\u3400'..'\u9fff' }
            }
        } ?: return null
        return x0 + hit.rect.centerX to hit.rect.centerY
    }

    private fun normalize(text: String): String =
        TpCatalog.normalizeAreaName(text).filterNot { it.isWhitespace() }
}
