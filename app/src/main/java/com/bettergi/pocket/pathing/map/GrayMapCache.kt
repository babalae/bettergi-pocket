package com.bettergi.pocket.pathing.map

import org.opencv.core.Mat

/**
 * 精匹配灰度图的按需加载缓存（按字节数 LRU 淘汰）。
 *
 * BGI 在加载时解码全部 342 张灰度图并常驻（约 500 MiB）。但灰度图只在精匹配时使用，
 * 且只用粗匹配选中的那一层，所以这里改为首次用到时解码，超出 [maxBytes] 时淘汰最久未用的图层。
 * 匹配结果不变，代价是首次进入某个图层时有一次解码耗时（最大的 5120×10240 图约数百毫秒）。
 */
class GrayMapCache(private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val entries = LinkedHashMap<BaseMapLayerByTemplateMatch, Mat>(16, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(layer: BaseMapLayerByTemplateMatch, loader: () -> Mat): Mat {
        entries[layer]?.let { return it }
        val mat = loader()
        val bytes = mat.total() * mat.elemSize()
        entries[layer] = mat
        totalBytes += bytes
        evict(keep = layer)
        return mat
    }

    @Synchronized
    fun loadedBytes(): Long = totalBytes

    @Synchronized
    fun clear() {
        entries.values.forEach { it.release() }
        entries.clear()
        totalBytes = 0
    }

    private fun evict(keep: BaseMapLayerByTemplateMatch) {
        val iterator = entries.entries.iterator()
        while (totalBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key === keep) continue
            totalBytes -= entry.value.total() * entry.value.elemSize()
            entry.value.release()
            iterator.remove()
        }
    }

    companion object {
        /** 足以同时容纳最大的几张主地图（MapBack_0~3 合计约 160 MiB）及若干城市/分层图。 */
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
    }
}
