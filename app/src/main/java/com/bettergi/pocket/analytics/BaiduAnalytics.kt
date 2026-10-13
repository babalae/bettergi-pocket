package com.bettergi.pocket.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import com.baidu.mobstat.StatService
import com.bettergi.pocket.R

/** 仅由主进程的 Application 准备配置，不在 Application 中触发启动统计。 */
object BaiduAnalytics {
    const val PAGE_OVERLAY_PANEL = "悬浮面板"

    private var session: AnalyticsSession? = null
    var isConfigured: Boolean = false
        private set

    fun prepare(context: Context) {
        if (session != null) return
        val appContext = context.applicationContext
        val appKey = appContext.getString(R.string.baidu_mtj_app_key).trim()
        val channel = appContext.getString(R.string.baidu_mtj_channel)
        isConfigured = appKey.isNotEmpty()
        session = AnalyticsSession(
            configured = isConfigured,
            backend = object : AnalyticsBackend {
                override fun start() {
                    // 未配置 AppKey 时不会调用 SDK，只配置了 AppKey 才初始化并启动统计。
                    StatService.setAuthorizedState(appContext, true)
                    StatService.browseMode(false)
                    StatService.setDebugOn(
                        appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                    )
                    StatService.init(appContext, appKey, channel)
                    StatService.start(appContext)
                }

                override fun pageStart(name: String) = StatService.onPageStart(appContext, name)
                override fun pageEnd(name: String) = StatService.onPageEnd(appContext, name)
                override fun event(id: String, label: String) =
                    StatService.onEvent(appContext, id, label, 1)
            },
        )
    }

    fun start() {
        session?.start()
    }

    fun pageStart(name: String) {
        session?.pageStart(name)
    }

    fun pageEnd(name: String) {
        session?.pageEnd(name)
    }

    fun event(id: String, label: String) {
        session?.event(id, label)
    }
}
