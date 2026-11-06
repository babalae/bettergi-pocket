# Windows 真机自动化

在项目根目录运行 `powershell -ExecutionPolicy Bypass -File tools/android-device.ps1 <动作>`。也支持 PowerShell 7。SDK 从环境变量、local.properties 或默认路径寻找；构建优先使用 Android Studio JBR / Gradle 缓存 JDK，避免系统 Java 8。无需修改全局 PATH。

## 首次连接

1. 平板开启开发者选项、USB 调试，解锁并确认电脑的 RSA 调试授权。`doctor` 应显示 `device`；`unauthorized` 表示尚未确认，`offline` 可重新插线。没有设备时检查数据线、Windows 厂商驱动，以及 USB 是否被 VMware 等虚拟机接管。
2. `deploy` 构建 debug APK，按设备 ABI 安装覆盖（保留数据），授予悬浮窗和通知权限，然后启动悬浮球。
3. `permissions` 启用本应用无障碍，保留其他无障碍服务；通过 `status` 的 Bound services 和日志确认实际连接。厂商系统拒绝 ADB 授权时，在系统设置手动允许受限制设置并启用“更好的原神”。
4. `game` 启动已安装原神；多个版本时用 `-GamePackage com.miHoYo.Yuanshen` 指定。
5. 打开悬浮球，点击共享屏幕，在 Android 系统弹窗中确认共享整个屏幕或原神。这项授权每次会话可能需要重新确认。首次进入游戏的登录、更新和协议由用户完成。

## 常用命令

```powershell
./tools/android-device.ps1 doctor
./tools/android-device.ps1 build
./tools/android-device.ps1 deploy
./tools/android-device.ps1 deploy -SkipBuild
./tools/android-device.ps1 permissions
./tools/android-device.ps1 launch
./tools/android-device.ps1 game
./tools/android-device.ps1 capture
./tools/android-device.ps1 capture -IncludeUiDump
./tools/android-device.ps1 a11y-status
./tools/android-device.ps1 a11y-tap -X 330 -Y 200 -Duration 60
./tools/android-device.ps1 tap -X 500 -Y 400
./tools/android-device.ps1 swipe -X 500 -Y 800 -X2 500 -Y2 200 -Duration 400
./tools/android-device.ps1 key -KeyCode KEYCODE_BACK
./tools/android-device.ps1 status
./tools/android-device.ps1 logs
```

多设备时加 `-Serial 设备序列号`。截图坐标使用当前屏幕像素，横竖屏变化后重新截图，避免盲点。`capture` 保存 PNG、活动、无障碍状态、MediaProjection 会话和最近日志到 `artifacts/device/时间-序列号/`。游戏内部画面一般不会提供 UI XML 节点，需查看截图定位。

`-IncludeUiDump` 额外采集 UI XML。真机观察到 UIAutomator 会暂时抑制应用无障碍服务并使其重新连接，导致前台检测状态丢失，所以默认关闭。系统弹窗可按需使用此选项，但不要在自动化手势或前台检测测试期间开启。

`a11y-tap` 经 Debug 专用广播入口调用应用的 `InputAccessibilityService.click` → 无障碍桥接 → `GestureComposer`，可验证应用自身的手势。入口只存在于 `src/debug`，受系统 `android.permission.DUMP` 保护；Release 包不包含。命令检查 `gestureAccepted=true`，不成功时返回失败。提交成功仍须结合截图确认实际效果。坐标必须避开悬浮面板，入口直接测试手势层，不负责自动对话的识别或悬浮窗穿透逻辑。

每次覆盖安装会结束旧的屏幕共享会话，需要重新开启共享。`genshinForeground=null` 表示目前不能可靠确认前台应用，不等于无障碍断开；前台状态与 `dumpsys activity` 不一致时单独记录为功能问题，不据此声称前台检测通过。

ADB 的 tap/swipe 用于操作设备和配置测试现场。功能验证必须实际开启应用内对应开关，确认应用自身的无障碍手势生效；ADB 点按成功不等于应用无障碍通过。验证时保存操作前后截图和日志，并检查 `status` 中的无障碍绑定、前台服务和屏幕共享会话。不要把安装成功当成功能测试通过。

APK 位于 `app/build/outputs/apk/debug/`。脚本不卸载应用、不清数据、不解锁 bootloader、不 root。原神账号、系统授权弹窗、设备锁屏需要现场配合。

地图追踪的触控续接、手机标定、拾取和终点验证方法见 [真机调试经验](../docs/地图追踪-真机调试经验.md)。逐次测试报告和截图只保存在已忽略的 `artifacts/` 中。
