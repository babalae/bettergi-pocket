# 百度移动统计 Android SDK

- 版本：4.0.11.1，官网发布于 2026-06-16。
- 来源：[百度移动统计 SDK 下载页](https://mtj.baidu.com/web/sdk/index)。
- 官方压缩包：https://mtj.baidu.com/sdk/Android/BaiduMTJ_Android_App_Auto_V4.0.11.1.zip
- 使用文件：压缩包中的 `libs/Baidu_Mtj_android_4.0.11.1.jar`，未修改。
- JAR SHA-256：`b385597e4da1e6b9aa19d76b24c0a315f4c09e4bc276486a5d5f4e5ddb0d9ea8`。

官网下载的无埋点版兼容手动埋点 API。本项目仅使用手动 API，不调用 `autoTrace`，不引入 native crash 库。
Maven Central 的 `com.baidu.mobstat:mtj-sdk` 截至接入时仅提供至 4.0.7.6，因此固定使用官网的新版 JAR。

升级时从官网重新下载 JAR，同时更新构建依赖、`.gitignore` 中的文件例外和本文件的版本、来源、校验值，并重新评估 README 中的隐私说明。
