package com.bettergi.pocket.pathing.map

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel

/**
 * 地图追踪资源的读取入口，路径相对资源根目录（APK 内为 `assets/pathing/`），例如 `Map/Teyvat/mapback_info.json`。
 *
 * 大文件（最大 77 MB 的 `.mat.png`）通过 [map] 拿到直接缓冲区再交给 OpenCV 解码，
 * 能 mmap 时不经过 Java 堆。对应 BGI 中 `Global.Absolute(@"Assets\Map\")` 下的文件读取。
 */
interface MapAssetSource {
    /** 目录下的直接子文件名（不含子目录），目录不存在时返回空。 */
    fun list(dir: String): List<String>

    fun exists(path: String): Boolean

    /** 返回只读的直接缓冲区，内容为文件全部字节，position = 0。 */
    fun map(path: String): ByteBuffer

    fun readText(path: String): String {
        val buffer = map(path)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** 用 OpenCV 解码图片（对应 BGI `Bv.ImRead` / `new Mat(path, mode)`）。失败返回空 Mat。 */
    fun decodeImage(path: String, flags: Int): Mat {
        val buffer = map(path)
        val encoded = Mat(1, buffer.remaining(), CvType.CV_8UC1, buffer)
        try {
            return Imgcodecs.imdecode(encoded, flags)
        } finally {
            encoded.release()
            // encoded 只借用 buffer 的内存；这里再访问一次 buffer，保证解码结束前它不会被回收
            buffer.position(0)
        }
    }
}

/** 文件系统实现：JVM 单测直接读 `app/src/main/assets/pathing`。 */
class FileMapAssetSource(private val root: File) : MapAssetSource {
    override fun list(dir: String): List<String> {
        val folder = File(root, dir)
        return folder.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
    }

    override fun exists(path: String): Boolean = File(root, path).isFile

    override fun map(path: String): ByteBuffer {
        RandomAccessFile(File(root, path), "r").use { file ->
            return file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
    }
}

/**
 * Android 实现。资源需在 build.gradle.kts 中声明 noCompress，才能通过 `openFd` 直接 mmap APK 中的数据；
 * 被压缩的资源退回到流式读取（会占用 Java 堆）。
 */
class AndroidMapAssetSource(
    private val assets: android.content.res.AssetManager,
    private val root: String = "pathing",
) : MapAssetSource {
    override fun list(dir: String): List<String> {
        val path = join(dir)
        return try {
            assets.list(path)?.filter { name ->
                // AssetManager.list 不区分文件和目录：能打开的才是文件
                try {
                    assets.open("$path/$name").close()
                    true
                } catch (_: Exception) {
                    false
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun exists(path: String): Boolean {
        return try {
            assets.open(join(path)).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun map(path: String): ByteBuffer {
        val full = join(path)
        try {
            assets.openFd(full).use { afd ->
                afd.createInputStream().use { input ->
                    return input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            // 资源被压缩时 openFd 抛 FileNotFoundException，改为流式读取
        }
        assets.open(full).use { input -> return readFully(input) }
    }

    private fun join(path: String): String = if (path.isEmpty()) root else "$root/$path"
}

internal fun readFully(input: InputStream): ByteBuffer {
    var buffer = ByteBuffer.allocateDirect(maxOf(input.available(), 64 * 1024)).order(ByteOrder.nativeOrder())
    val channel = Channels.newChannel(input)
    while (true) {
        if (!buffer.hasRemaining()) {
            val bigger = ByteBuffer.allocateDirect(buffer.capacity() * 2).order(ByteOrder.nativeOrder())
            buffer.flip()
            bigger.put(buffer)
            buffer = bigger
        }
        if (channel.read(buffer) < 0) break
    }
    buffer.flip()
    return buffer
}
