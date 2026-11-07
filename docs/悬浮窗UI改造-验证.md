# 悬浮窗 UI 改造验证

日期：2026-11-07

本次按 [设计文档](悬浮窗UI改造-设计.md) 的默认方案实现。主面板、三态状态条、路线浏览及执行队列、设置、同窗角色候选、权限页均已替换。悬浮球资源及拖动吸边、闲置淡出行为保留，识别与触控执行流程未改；执行入口共用只读前置检查，批执行器增加只读进度回调。

## 构建与 JVM

- `gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`：通过，生成 arm64-v8a、armeabi-v7a、x86_64 调试包。
- `gradlew.bat :app:testDebugUnitTest`：171 项通过，0 失败，0 错误。新增覆盖队列压缩及执行顺序、目录移除、结构化进度与汇总、毫秒精度与输入溢出。
- `git diff --check`：通过。

## Android 仪器验证

在 Android API 36.1 x86_64 模拟器和手机 24117RK2CC（arm64）上分别运行以下三项，均通过：

1. `OverlayLayoutInstrumentedTest`：用 Android 原生测量、RecyclerView 和 Canvas 渲染尺寸矩阵，不修改真机屏幕参数。
2. `AvatarMultiSelectInstrumentedTest`：旧设置迁移、中文/英文/别名搜索入口、跨搜索保留、即时保存、无障碍排序、清空及撤销、恢复默认、短E与长E互斥；确认浏览不取焦点，触摸搜索后临时取得焦点，完成后归还。
3. `OverlayStatusInstrumentedTest`：真实 WindowManager 下的主面板、启动拒绝时保留面板、隐藏自动进入精简模式、进度、单次停止回调、展开正文、正文始终不可触摸、标题命中手势时临时穿透及恢复。

| 尺寸（dp） | 字体缩放 | 结果 |
|---|---|---|
| 640×320 | 1.0 / 1.3 | 通过，路线列表至少 5 行 |
| 800×360 | 1.0 / 1.3 | 通过，路线列表至少 5 行、角色网格至少 25 人 |
| 873×393 | 1.0 / 1.3 | 通过 |
| 1113×697 | 1.0 / 1.3 | 通过 |
| 360×800 | 1.0 / 1.3 | 通过，使用单栏和队列入口 |

另外在模拟器上以 `wm size 1600x720`、`wm density 320` 验证真实 800×360dp 窗口。结束后重置模拟器屏幕参数并关闭本次启动的模拟器。手机保持原有分辨率、密度及字体；测试修改的候选、路线、窗口偏好已恢复，独立测试包已移除，新版应用保留安装并启动悬浮球。

原生渲染截图在本地 `artifacts/overlay-ui/overlay-ui/`，真实窗口截图在 `artifacts/overlay-ui/`，手机截图在 `artifacts/overlay-ui/phone/`；这些目录按项目约定不进入 Git。

## lint 与尚未覆盖的范围

`gradlew.bat :app:lintDebug` 仍因两处原有错误失败，相关执行代码没有在本次 UI 改造中修改：

- `InputAccessibilityService.kt:161`：`UsageStatsManager.queryEvents` 缺少 `PACKAGE_USAGE_STATS` 权限声明。
- `GestureComposer.kt:386`：API 24 分支调用了 API 26 的四参数 `StrokeDescription` 构造函数。

本次新增代码没有 lint Error；`UnusedResources` 仅剩原有 `black`、`white` 两项，没有新增。旧页面及不再使用的 UI drawable 已清理。

未在原神内实际执行路线，因此不将停止回调和穿透标记测试等同于实战中的触点释放回归；本次没有更改触点注入和释放逻辑。权限说明页已通过构建，未在手机上撤销权限后重走授权流程。
