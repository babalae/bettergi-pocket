package com.bettergi.pocket.pathing.party

import android.content.res.AssetManager
import org.opencv.core.Mat

/** 每个路线工作线程拥有一个实例，关闭时释放原生模型。 */
class NcnnAvatarClassifier(assets: AssetManager, private val catalog: AvatarCatalog) : AutoCloseable {
    private var handle: Long

    init {
        System.loadLibrary("pocket_ncnn")
        handle = nativeCreate(assets)
        check(handle != 0L) { "ncnn 头像模型加载失败" }
    }

    @Synchronized
    fun classify(bgr: Mat): Pair<PartyAvatar, Float>? {
        check(handle != 0L) { "头像识别器已经关闭" }
        require(bgr.channels() == 3)
        val pixels = ByteArray(bgr.rows() * bgr.cols() * 3)
        bgr.get(0, 0, pixels)
        val probabilities = nativeClassify(handle, pixels, bgr.cols(), bgr.rows()) ?: error("ncnn 头像推理失败")
        check(probabilities.size == catalog.labels.size) { "头像模型输出数量不匹配" }
        val top = probabilities.indices.maxBy { probabilities[it] }
        val label = catalog.labels[top]
        val confidence = probabilities[top]
        val threshold = if (label.startsWith("Qin") || "Costume" in label) 0.51f else 0.7f
        if (!confidence.isFinite() || confidence < threshold) return null
        return catalog.byLabel(label)?.let { it to confidence }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(assets: AssetManager): Long
    private external fun nativeClassify(handle: Long, pixels: ByteArray, width: Int, height: Int): FloatArray?
    private external fun nativeDestroy(handle: Long)
}
