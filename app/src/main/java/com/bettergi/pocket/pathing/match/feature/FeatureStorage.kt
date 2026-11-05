package com.bettergi.pocket.pathing.match.feature

import com.bettergi.pocket.pathing.map.MapAssetSource
import org.opencv.core.CvType
import org.opencv.imgcodecs.Imgcodecs
import java.nio.ByteOrder

/**
 * 读取 BGI 预先生成的 SIFT 特征文件。
 * 来源：BGI `Core/Recognition/OpenCv/FeatureMatch/FeatureStorageHelper.cs`。
 *
 * - `*.kp.bin`：无文件头的 `cv::KeyPoint` 数组，每条 28 字节，小端：
 *   pt.x, pt.y, size, angle, response（float）, octave, class_id（int32）
 * - `*.mat.png`：128 列 8 位灰度 PNG，每行一个描述子
 */
object FeatureStorage {
    private const val KEY_POINT_BYTES = 28

    fun loadKeyPoints(source: MapAssetSource, path: String): KeyPointPoints {
        val buffer = source.map(path).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.remaining()
        if (length % KEY_POINT_BYTES != 0) {
            throw IllegalStateException("无法识别的KeyPoint格式：$path")
        }
        val n = length / KEY_POINT_BYTES
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val offset = i * KEY_POINT_BYTES
            xs[i] = buffer.getFloat(offset)
            ys[i] = buffer.getFloat(offset + 4)
        }
        return KeyPointPoints(xs, ys)
    }

    fun loadDescriptors(source: MapAssetSource, path: String, cacheFloat: Boolean): DescriptorMatrix {
        val mat = source.decodeImage(path, Imgcodecs.IMREAD_GRAYSCALE)
        if (mat.empty() || mat.type() != CvType.CV_8UC1) {
            mat.release()
            throw IllegalStateException("地图数据加载失败，文件: $path")
        }
        return DescriptorMatrix(mat, cacheFloat)
    }
}
