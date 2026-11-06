package com.bettergi.pocket.pathing.executor

import com.bettergi.pocket.pathing.model.PathingResult
import com.bettergi.pocket.pathing.model.PathingRoute
import com.bettergi.pocket.pathing.model.PathingRoutes
import com.bettergi.pocket.pathing.settings.PathingRouteSource
import java.util.concurrent.CancellationException

/** 每条路线独立读取、校验和执行。不支持的路线写日志后跳过，取消始终向上传播。 */
class RouteBatchRunner(
    private val checkCancelled: () -> Unit,
    private val log: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    data class Summary(val completed: Int, val skipped: Int, val failed: Int) {
        val message: String get() = "路线执行结束：完成 $completed，跳过 $skipped，失败 $failed"
    }

    fun run(sources: List<PathingRouteSource>, execute: (PathingRoute) -> PathingResult): Summary {
        var completed = 0
        var skipped = 0
        var failed = 0
        for ((index, source) in sources.withIndex()) {
            checkCancelled()
            val name = source.name
            onStatus("${index + 1}/${sources.size} · $name")
            try {
                val route = PathingRoutes.parse(source.readText())
                val reason = PathingRoutes.rejectionReason(route)
                    ?: if (route.waypoints.isEmpty()) "路线没有路径点" else null
                if (reason != null) {
                    skipped++
                    log("跳过路线 $name：$reason")
                    continue
                }
                checkCancelled()
                log("开始路线 $name")
                val result = execute(route)
                checkCancelled()
                if (result.isSuccess) {
                    completed++
                    log("$name 完成")
                } else {
                    // 与 BGI 调度一致：一条失败不影响后面的路线，停止时才退出。
                    failed++
                    log("$name 未完成：${result.errorMessage ?: "未知原因"}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                checkCancelled()
                failed++
                log("跳过路线 $name：${e.message ?: e.javaClass.simpleName}")
            }
        }
        return Summary(completed, skipped, failed).also { log(it.message) }
    }
}
