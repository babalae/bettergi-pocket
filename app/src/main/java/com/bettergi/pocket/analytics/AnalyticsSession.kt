package com.bettergi.pocket.analytics

/** 统一控制统计启动与页面配对，任何统计调用都必须经过这里。 */
internal class AnalyticsSession(
    private val configured: Boolean,
    private val backend: AnalyticsBackend,
) {
    private var running = false
    private val activePages = mutableSetOf<String>()

    fun start(): Boolean {
        if (!configured) return false
        if (!running) {
            backend.start()
            running = true
        }
        return true
    }

    fun pageStart(name: String) {
        if (!running || name.isBlank() || !activePages.add(name)) return
        backend.pageStart(name)
    }

    fun pageEnd(name: String) {
        if (!running || !activePages.remove(name)) return
        backend.pageEnd(name)
    }

    fun event(id: String, label: String) {
        if (!running || id.isBlank()) return
        backend.event(id, label)
    }
}

internal interface AnalyticsBackend {
    fun start()
    fun pageStart(name: String)
    fun pageEnd(name: String)
    fun event(id: String, label: String)
}
