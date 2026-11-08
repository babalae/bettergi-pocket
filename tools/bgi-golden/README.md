# BetterGI 对拍基准生成器

调用 BetterGI 编译产物（`BetterGI.dll`）里的原实现，生成 Pocket 地图追踪 JVM 单测使用的输入图和期望值，
输出到 `app/src/test/resources/pathing/golden/`。

## 生成内容

- `mm_XX.png`：从 BetterGI 单测截图（1920×1080）裁出的小地图 `(62, 19, 212, 212)`
- `mm_XX_pre.png` / `mm_XX_mask.png`：`MiniMapPreprocessor.GetMiniMapAndMask` 的输出
- `bm_*.png`：从 `Teyvat_0_256.png` 裁出的 480×270 灰度图，放大 4 倍即为合成大地图
- `golden.json`：视角（含 Gia 兜底）、TemplateMatch 全局/局部定位、SIFT 局部/全局定位、大地图位置与矩形

当前基准使用 `BetterGI.Assets.Map 1.0.26`（366 个 TemplateMatch 图层），与
`tools/prepare-ci-pathing-assets.ps1` 一致。`golden.json` 中的 `templateLayerCount` 由生成器记录。
升级 CI 地图资源包时，先将同一版本资源同步到 BetterGI 编译目录，再重新生成基准并同步 Pocket 地图资源；
新增图层可能改变全局最佳匹配，不能只更新图层数量断言。

## 运行

需要 .NET 10 SDK，并且 BetterGI 已在本机编译（默认目录见 `BgiGolden.csproj` 的 `BgiBin`）。在仓库根目录执行：

```powershell
dotnet build tools\bgi-golden\BgiGolden.csproj -c Release -p:Platform=x64
tools\bgi-golden\bin\x64\Release\net10.0-windows10.0.22621.0\BgiGolden.exe
```

可选参数：

| 参数 | 说明 |
|---|---|
| `--bgi <目录>` | BetterGI 编译输出目录 |
| `--tests <目录>` | BetterGI 单测资源目录 `Test\BetterGenshinImpact.UnitTest\Assets` |
| `--out <目录>` | 输出目录 |
| `--sift-global <n>` | 做 SIFT 全局匹配（FLANN，较慢）的图片数量，默认 3 |

运行前会清空输出目录。BetterGI 的算法或地图资源更新后，重新生成基准并同步地图资源（`tools/sync-pathing-assets.ps1`）。
