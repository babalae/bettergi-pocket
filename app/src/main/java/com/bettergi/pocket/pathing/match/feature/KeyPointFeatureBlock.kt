package com.bettergi.pocket.pathing.match.feature

import com.bettergi.pocket.pathing.common.csRoundToInt
import com.bettergi.pocket.recognition.IntSize
import org.opencv.core.Mat
import org.opencv.core.Rect

/**
 * 一个网格内的特征点下标（对应 BGI `KeyPointFeatureBlock` 的 `KeyPointIndexList`）。
 *
 * BGI 在切块时还会为每块生成描述子副本，但合并时只用全局描述子按下标复制，块内副本从未被读取，
 * 这里不生成，结果相同。
 */
class KeyPointFeatureBlock {
    private var indices = IntArray(16)
    var count: Int = 0
        private set

    fun add(index: Int) {
        if (count == indices.size) indices = indices.copyOf(indices.size * 2)
        indices[count++] = index
    }

    fun indices(): IntArray = indices.copyOf(count)

    internal fun appendTo(target: IntArrayBuilder) {
        target.addAll(indices, count)
    }
}

/** 合并后的特征（对应 BGI 合并后的 `KeyPointFeatureBlock`）。 */
class MergedFeatures(
    val keyPoints: KeyPointPoints,
    /** CV_32FC1，与 [keyPoints] 一一对应 */
    val descriptor: Mat,
    val mergedCenterCellRow: Int,
    val mergedCenterCellCol: Int,
) : AutoCloseable {
    val isEmpty: Boolean get() = keyPoints.size == 0

    override fun close() {
        descriptor.release()
    }
}

internal class IntArrayBuilder {
    private var data = IntArray(256)
    var size = 0
        private set

    fun addAll(values: IntArray, count: Int) {
        if (size + count > data.size) {
            var capacity = data.size
            while (capacity < size + count) capacity *= 2
            data = data.copyOf(capacity)
        }
        System.arraycopy(values, 0, data, size, count)
        size += count
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/**
 * 来源：BGI `Core/Recognition/OpenCv/FeatureMatch/KeyPointFeatureBlockHelper.cs`。
 */
object KeyPointFeatureBlockHelper {
    fun splitFeatures(originalImage: IntSize, rows: Int, cols: Int, keyPoints: KeyPointPoints): Array<Array<KeyPointFeatureBlock?>> {
        val cellWidth = originalImage.width / cols
        val cellHeight = originalImage.height / rows
        val blocks = Array(rows) { arrayOfNulls<KeyPointFeatureBlock>(cols) }
        for (i in 0 until keyPoints.size) {
            // C# (int)(float / int)：向零截断
            var row = (keyPoints.ys[i] / cellHeight).toInt()
            var col = (keyPoints.xs[i] / cellWidth).toInt()
            row = minOf(maxOf(row, 0), rows - 1)
            col = minOf(maxOf(col, 0), cols - 1)
            val block = blocks[row][col] ?: KeyPointFeatureBlock().also { blocks[row][col] = it }
            block.add(i)
        }
        return blocks
    }

    fun getCellIndex(originalImage: IntSize, rows: Int, cols: Int, x: Float, y: Float): Pair<Int, Int> {
        val cellWidth = originalImage.width / cols
        val cellHeight = originalImage.height / rows
        val cellRow = csRoundToInt(y / cellHeight)
        val cellCol = csRoundToInt(x / cellWidth)
        return cellRow to cellCol
    }

    /** @return (rowStart, rowEnd, colStart, colEnd) */
    fun getCellRange(originalImage: IntSize, rows: Int, cols: Int, rect: Rect): IntArray {
        val cellWidth = originalImage.width / cols
        val cellHeight = originalImage.height / rows
        var rowStart = rect.y / cellHeight
        var rowEnd = (rect.y + rect.height) / cellHeight
        var colStart = rect.x / cellWidth
        var colEnd = (rect.x + rect.width) / cellWidth

        rowStart = maxOf(0, minOf(rowStart, rows - 1))
        rowEnd = maxOf(0, minOf(rowEnd, rows - 1))
        colStart = maxOf(0, minOf(colStart, cols - 1))
        colEnd = maxOf(0, minOf(colEnd, cols - 1))
        if (rowEnd < rowStart) rowEnd = rowStart
        if (colEnd < colStart) colEnd = colStart
        return intArrayOf(rowStart, rowEnd, colStart, colEnd)
    }

    fun mergeFeaturesInRange(
        blocks: Array<Array<KeyPointFeatureBlock?>>,
        keyPoints: KeyPointPoints,
        descriptors: DescriptorMatrix,
        rowStart: Int,
        rowEnd: Int,
        colStart: Int,
        colEnd: Int,
    ): MergedFeatures {
        val rows = blocks.size
        if (rows == 0) {
            return MergedFeatures(KeyPointPoints.EMPTY, descriptors.gatherFloat(IntArray(0)), -1, -1)
        }
        val cols = blocks[0].size
        val r0 = maxOf(0, minOf(rowStart, rows - 1))
        val r1 = maxOf(0, minOf(rowEnd, rows - 1))
        val c0 = maxOf(0, minOf(colStart, cols - 1))
        val c1 = maxOf(0, minOf(colEnd, cols - 1))
        val merged = IntArrayBuilder()
        for (i in r0..r1) {
            for (j in c0..c1) {
                blocks[i][j]?.appendTo(merged)
            }
        }
        val indices = merged.toArray()
        return MergedFeatures(
            keyPoints = keyPoints.subset(indices),
            descriptor = descriptors.gatherFloat(indices),
            mergedCenterCellRow = (r0 + r1) / 2,
            mergedCenterCellCol = (c0 + c1) / 2,
        )
    }

    fun mergeNeighboringFeatures(
        blocks: Array<Array<KeyPointFeatureBlock?>>,
        keyPoints: KeyPointPoints,
        descriptors: DescriptorMatrix,
        cellRow: Int,
        cellCol: Int,
    ): MergedFeatures {
        val merged = IntArrayBuilder()
        // 9 个相邻网格；中心越界时循环范围为空，与 BGI 一致
        for (i in maxOf(cellRow - 1, 0)..minOf(cellRow + 1, blocks.size - 1)) {
            for (j in maxOf(cellCol - 1, 0)..minOf(cellCol + 1, blocks[i].size - 1)) {
                blocks[i][j]?.appendTo(merged)
            }
        }
        val indices = merged.toArray()
        return MergedFeatures(
            keyPoints = keyPoints.subset(indices),
            descriptor = descriptors.gatherFloat(indices),
            mergedCenterCellRow = cellRow,
            mergedCenterCellCol = cellCol,
        )
    }
}
