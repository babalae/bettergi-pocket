package com.bettergi.pocket.pathing.party

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettergi.pocket.pathing.PathingLayout
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.IntSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs

/** 直接在 Android ABI 上加载打包的 ncnn 模型；不操作游戏、不依赖屏幕共享。 */
@RunWith(AndroidJUnit4::class)
class NcnnPartyInstrumentedTest {
    @Test
    fun classifiesCutoutPhoneWithShiftedPartyStrip() {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.targetContext.assets
        val encoded = MatOfByte(*instrumentation.context.assets.open("party/cutout-party.png").use { it.readBytes() })
        val image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        encoded.release()
        val previous = com.bettergi.pocket.recognition.area.GameUiCalibration.current
        com.bettergi.pocket.recognition.area.GameUiCalibration.current = com.bettergi.pocket.recognition.area.GameUiGeometry(
            IntSize(2400,1080), IntSize(3040,1904), 0.6307563, 89.7245, 89.7245)
        try {
            val layout = PathingLayout.parse(assets.open("pathing/layout.json").bufferedReader().use { it.readText() })
            NcnnAvatarClassifier(assets, AvatarCatalog.load(assets)).use { classifier ->
                val recognition = PartyRecognition(layout, classifier)
                repeat(2) {
                    val party = recognition.recognize(image)
                    assertNotNull("宽屏手机应自动定位三个头像", party)
                    assertEquals(listOf("绮良良", "八重神子", "哥伦比娅"), party!!.visible.map { it.avatar.name })
                    assertTrue(party.visible.first().bounds.y < 240)
                }
                val blank = org.opencv.core.Mat.zeros(image.rows(), image.cols(), image.type())
                try { assertNull(recognition.recognize(blank)) } finally { blank.release() }
            }
        } finally {
            com.bettergi.pocket.recognition.area.GameUiCalibration.current = previous
            image.release()
        }
    }

    @Test
    fun classifiesFromRestrictedWorkerAcrossRouteRestarts() {
        val task = java.util.concurrent.FutureTask {
            val tid = android.os.Process.myTid()
            val status = java.io.File("/proc/self/task/$tid/status")
            fun affinity(): java.math.BigInteger {
                val mask = status.readLines().first { it.startsWith("Cpus_allowed:") }
                    .substringAfter(':').trim().replace(",", "")
                return java.math.BigInteger(mask, 16)
            }
            fun setAffinity(mask: java.math.BigInteger) {
                val process = ProcessBuilder("/system/bin/taskset", "-p", mask.toString(16), tid.toString())
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 0, process.waitFor())
                assertEquals("工作线程 CPU 亲和性设置失败：$output", mask, affinity())
            }
            val original = affinity()
            try {
                // 模拟 Android 限制后台工作线程可用 CPU；第一次模型加载也发生在此线程。
                setAffinity(original.and(original.negate()))
                repeat(3) {
                    // 每条路线重新创建、推理并释放模型，同时验证头像结果和空白拒识。
                    classifiesPhonePartyAndRejectsBlankScreen()
                }
            } finally {
                setAffinity(original)
            }
        }
        val worker = Thread(task, "PathingWorkerRegression")
        worker.start()
        try {
            task.get(60, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            worker.interrupt()
            worker.join(5000)
        }
    }

    @Test
    fun classifiesPhonePartyAndRejectsBlankScreen() {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.targetContext.assets
        val catalog = AvatarCatalog.load(assets)
        assertEquals(162, catalog.labels.size)
        val encoded = MatOfByte(*instrumentation.context.assets.open("party/phone-party.png").use { it.readBytes() })
        val image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        encoded.release()
        val layout = PathingLayout.parse(assets.open("pathing/layout.json").bufferedReader().use { it.readText() })
        try {
            NcnnAvatarClassifier(assets, catalog).use { classifier ->
                val recognition = PartyRecognition(layout, classifier)
                val party = recognition.recognize(image)
                assertNotNull(party)
                assertEquals(listOf("桑多涅", "七七", "奥黛塔"), party!!.visible.map { it.avatar.name })
                assertTrue(party.visible.all { it.confidence >= 0.7f })
                // 不连续的 ROI Mat 也必须正确转成 JNI 的连续 BGR 数组。
                assertTrue(party.valid)
                val blank = org.opencv.core.Mat.zeros(image.rows(), image.cols(), image.type())
                try { assertNull(recognition.recognize(blank)) } finally { blank.release() }
            }
        } finally { image.release() }
    }
}
