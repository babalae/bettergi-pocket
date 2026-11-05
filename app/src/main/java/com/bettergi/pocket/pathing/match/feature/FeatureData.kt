package com.bettergi.pocket.pathing.match.feature

import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * 训练特征点坐标。BGI 用 `KeyPoint[]`，但匹配只用到 `Pt`，这里只保留 x/y 以节省内存
 * （2048 图层有 73 万个特征点）。
 */
class KeyPointPoints(val xs: FloatArray, val ys: FloatArray) {
    init {
        require(xs.size == ys.size)
    }

    val size: Int get() = xs.size

    fun subset(indices: IntArray): KeyPointPoints {
        return KeyPointPoints(FloatArray(indices.size) { xs[indices[it]] }, FloatArray(indices.size) { ys[indices[it]] })
    }

    companion object {
        val EMPTY = KeyPointPoints(FloatArray(0), FloatArray(0))
    }
}

/**
 * SIFT 描述子矩阵（N × 128）。
 *
 * BGI 加载时直接转成 CV_32F 常驻（2048 图层约 357 MiB），切块时还会为每块再复制一份。
 * 描述子来自 8 位 PNG，取值都是 0~255 的整数，所以这里以 CV_8U 常驻，
 * 需要时再转 float，结果与 BGI 完全相同。
 *
 * @param cacheFloat 为 true 时缓存整张 float 矩阵（小图层用）
 */
class DescriptorMatrix(private val u8: Mat, private val cacheFloat: Boolean) : AutoCloseable {
    init {
        require(u8.type() == CvType.CV_8UC1) { "描述子必须是 8 位单通道，实际 ${CvType.typeToString(u8.type())}" }
    }

    val rows: Int get() = u8.rows()
    val cols: Int get() = u8.cols()

    private var cachedFloat: Mat? = null

    /**
     * 整张描述子矩阵的 float 版本。
     * [cacheFloat] 为 false 时每次新建，调用方用 [releaseIfTemporary] 释放。
     */
    @Synchronized
    fun floatMat(): Mat {
        cachedFloat?.let { return it }
        val mat = Mat()
        u8.convertTo(mat, CvType.CV_32FC1)
        if (cacheFloat) cachedFloat = mat
        return mat
    }

    fun releaseIfTemporary(mat: Mat) {
        if (mat !== cachedFloat) mat.release()
    }

    /**
     * 按行号抽取描述子并转成 float（对应 BGI `KeyPointFeatureBlockHelper.InitBlockMat`）。
     * @return 新的 CV_32FC1 矩阵，调用方释放
     */
    fun gatherFloat(indices: IntArray): Mat {
        val out = Mat(indices.size, cols, CvType.CV_32FC1)
        if (indices.isEmpty()) return out
        val rowBytes = ByteArray(cols)
        val chunkRows = 2048
        val chunk = FloatArray(chunkRows * cols)
        var start = 0
        while (start < indices.size) {
            val count = minOf(chunkRows, indices.size - start)
            for (r in 0 until count) {
                u8.get(indices[start + r], 0, rowBytes)
                val base = r * cols
                for (c in 0 until cols) {
                    chunk[base + c] = (rowBytes[c].toInt() and 0xFF).toFloat()
                }
            }
            if (count == chunkRows) {
                out.put(start, 0, chunk)
            } else {
                out.put(start, 0, chunk.copyOf(count * cols))
            }
            start += count
        }
        return out
    }

    @Synchronized
    override fun close() {
        cachedFloat?.release()
        cachedFloat = null
        u8.release()
    }
}
