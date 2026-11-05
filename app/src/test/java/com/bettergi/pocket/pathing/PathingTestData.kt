package com.bettergi.pocket.pathing

import com.bettergi.pocket.pathing.map.FileMapAssetSource
import com.bettergi.pocket.recognition.opencv.OpenCvRuntime
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * 地图追踪单测的公共数据：
 * - BGI 对拍基准：`src/test/resources/pathing/golden`，由 tools/bgi-golden 生成
 * - 地图资源：`src/main/assets/pathing`，由 tools/sync-pathing-assets.ps1 复制，不存在时跳过相关用例
 */
object PathingTestData {
    fun ensureOpenCv() {
        assertTrue("OpenCV failed to load for JVM unit tests", OpenCvRuntime.ensureLoaded())
    }

    val goldenDir: File by lazy {
        listOf(File("src/test/resources/pathing/golden"), File("app/src/test/resources/pathing/golden"))
            .first { it.isDirectory }
    }

    val golden: JSONObject by lazy { JSONObject(File(goldenDir, "golden.json").readText(Charsets.UTF_8)) }

    private val assetsRoot: File? by lazy {
        listOf(File("src/main/assets/pathing"), File("app/src/main/assets/pathing"))
            .firstOrNull { File(it, "Map/Teyvat/mapback_info.json").isFile }
    }

    /** 地图资源未同步时跳过用例。 */
    fun requireAssets(): FileMapAssetSource {
        val root = assetsRoot
        assumeTrue("地图资源未同步，运行 tools/sync-pathing-assets.ps1 后再测", root != null)
        return FileMapAssetSource(root!!)
    }

    fun readGolden(fileName: String, flags: Int = Imgcodecs.IMREAD_COLOR): Mat {
        val mat = Imgcodecs.imread(File(goldenDir, fileName).absolutePath, flags)
        assertTrue("无法读取 $fileName", !mat.empty())
        return mat
    }
}
