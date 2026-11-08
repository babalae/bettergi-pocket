using System.IO;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using BetterGenshinImpact.Core.Config;
using BetterGenshinImpact.GameTask.Common.Map;
using BetterGenshinImpact.GameTask.Common.Map.Camera;
using BetterGenshinImpact.GameTask.Common.Map.MiniMap;
using BetterGenshinImpact.GameTask.Common.Map.Maps;
using BetterGenshinImpact.GameTask.Common.Map.Maps.Base;
using OpenCvSharp;

namespace BgiGolden;

/// <summary>
/// 调用 BetterGI 原实现生成对拍基准。
///
/// 小地图：从 BetterGI 单测截图（1920x1080）裁出 MimiMapRect1080P = (62,19,212,212)，
/// 依次记录视角、预处理结果、TemplateMatch 全局/局部定位、SIFT 局部（和少量全局）定位。
/// 每张图开始前清空 TemplateMatch 的 PrevSuccessResult，Kotlin 侧按同样顺序重放。
///
/// 大地图：从 Teyvat_0_256.png 裁 480x270 灰度图，放大 4 倍得到 1920x1080 合成大地图，
/// 记录 GetBigMapPosition（无先验 / 有先验）和 GetBigMapRect。
/// </summary>
public static class Generator
{
    private static readonly Rect MiniMapRect1080P = new(62, 19, 212, 212);

    // Teyvat_0_256.png 上的合成大地图中心（256 图像坐标），取自 BGI CountryPositions 换算
    private static readonly (string Name, int X, int Y)[] BigMapCenters =
    [
        ("mondstadt", 4315, 2502),
        ("liyue", 4028, 3238),
        ("inazuma", 5196, 3834),
        ("sumeru", 3376, 3165),
        ("fontaine", 2967, 2164),
    ];

    public static int Run(Options options)
    {
        Global.StartUpPath = options.BgiBin;
        Directory.CreateDirectory(options.OutDir);
        foreach (var old in Directory.GetFiles(options.OutDir))
        {
            File.Delete(old);
        }

        var root = new JsonObject
        {
            ["source"] = "BetterGI " + options.BgiBin,
            ["generatedAt"] = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"),
        };

        Console.WriteLine("加载 TemplateMatch 图层...");
        var templateMap = new TeyvatMapTest();
        templateMap.Layers = BaseMapLayerByTemplateMatch.LoadLayers(templateMap);
        Console.WriteLine($"  图层数 {templateMap.Layers.Count}");
        root["templateLayerCount"] = templateMap.Layers.Count;

        Console.WriteLine("加载 SIFT 2048 图层...");
        var siftMap = new TeyvatMap();
        siftMap.Layers = BaseMapLayer.LoadLayers(siftMap);
        Console.WriteLine($"  图层数 {siftMap.Layers.Count}");

        root["miniMaps"] = GenerateMiniMaps(options, templateMap, siftMap);
        root["bigMaps"] = GenerateBigMaps(options, templateMap);

        var json = root.ToJsonString(new JsonSerializerOptions
        {
            WriteIndented = true,
            Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        });
        File.WriteAllText(Path.Combine(options.OutDir, "golden.json"), json);
        Console.WriteLine("完成：" + options.OutDir);
        return 0;
    }

    private static List<string> SelectScreenshots(Options options)
    {
        var fight = Directory.GetFiles(Path.Combine(options.TestAssets, "AutoFight"), "*.png", SearchOption.AllDirectories)
            .OrderBy(f => f, StringComparer.OrdinalIgnoreCase);
        var fishing = Directory.GetFiles(Path.Combine(options.TestAssets, "AutoFishing"), "*.png", SearchOption.AllDirectories)
            .OrderBy(f => f, StringComparer.OrdinalIgnoreCase)
            .Where((_, i) => i % 2 == 0);
        var result = new List<string>();
        foreach (var file in fight.Concat(fishing))
        {
            using var probe = Cv2.ImRead(file, ImreadModes.Unchanged);
            if (probe.Width == 1920 && probe.Height == 1080)
            {
                result.Add(file);
            }
        }
        return result;
    }

    private static JsonArray GenerateMiniMaps(Options options, TeyvatMapTest templateMap, TeyvatMap siftMap)
    {
        var array = new JsonArray();
        var files = SelectScreenshots(options);
        var siftGlobalCount = 0;
        for (var index = 0; index < files.Count; index++)
        {
            var name = $"mm_{index:D2}";
            Console.WriteLine($"[{name}] {Path.GetFileName(files[index])}");
            using var screen = Cv2.ImRead(files[index], ImreadModes.Color);
            using var miniMap = new Mat(screen, MiniMapRect1080P).Clone();
            Cv2.ImWrite(Path.Combine(options.OutDir, name + ".png"), miniMap);

            var item = new JsonObject
            {
                ["file"] = name + ".png",
                ["source"] = Path.GetRelativePath(options.TestAssets, files[index]),
            };

            // 视角
            using (var pre = new MiniMapPreprocessor())
            using (var input = miniMap.Clone())
            {
                var (angle, confidence) = pre.PredictRotationWithConfidence(input);
                item["rotation"] = new JsonObject { ["angle"] = angle, ["confidence"] = confidence };
            }
            using (var input = miniMap.Clone())
            {
                item["cameraOrientation"] = CameraOrientation.ComputeMiniMap(input);
            }
            using (var input = miniMap.Clone())
            {
                item["gia"] = CameraOrientationFromGia.ComputeMiniMap(input);
            }

            // 预处理结果（TemplateMatch 输入）
            using (var pre = new MiniMapPreprocessor())
            using (var input = miniMap.Clone())
            {
                var (processed, mask) = pre.GetMiniMapAndMask(input);
                Cv2.ImWrite(Path.Combine(options.OutDir, name + "_pre.png"), processed);
                Cv2.ImWrite(Path.Combine(options.OutDir, name + "_mask.png"), mask);
                processed.Dispose();
                mask.Dispose();
            }

            // TemplateMatch：全局 -> 以全局结果为 prev 的局部 -> 偏移 prev 的局部
            templateMap.PrevSuccessResult = default;
            var global = templateMap.GetMiniMapPosition(miniMap);
            item["templateGlobal"] = TemplateResult(templateMap, global);
            var local = templateMap.GetMiniMapPosition(miniMap, global.X, global.Y);
            item["templateLocal"] = TemplateResult(templateMap, local);
            var offsetPrev = new Point2f(global.X + 40, global.Y - 30);
            item["templateLocalOffsetPrev"] = new JsonArray(offsetPrev.X, offsetPrev.Y);
            var localOffset = templateMap.GetMiniMapPosition(miniMap, offsetPrev.X, offsetPrev.Y);
            item["templateLocalOffset"] = TemplateResult(templateMap, localOffset);

            // SIFT：局部（以 TemplateMatch 全局结果为 prev，BruteForce），少量全局（FLANN）
            if (global != default)
            {
                var siftLocal = siftMap.GetMiniMapPosition(miniMap, global.X, global.Y);
                item["siftLocal"] = Point(siftLocal);
                if (siftGlobalCount < options.SiftGlobalLimit)
                {
                    siftGlobalCount++;
                    var siftGlobal = siftMap.GetMiniMapPosition(miniMap);
                    item["siftGlobal"] = Point(siftGlobal);
                }
            }

            array.Add(item);
        }
        return array;
    }

    private static JsonArray GenerateBigMaps(Options options, TeyvatMapTest map)
    {
        var array = new JsonArray();
        using var full = Cv2.ImRead(Path.Combine(options.BgiBin, "Assets", "Map", "Teyvat", "Teyvat_0_256.png"), ImreadModes.Grayscale);
        foreach (var (name, cx, cy) in BigMapCenters)
        {
            var file = $"bm_{name}.png";
            Console.WriteLine($"[{file}]");
            var cropRect = new Rect(cx - 240, cy - 135, 480, 270);
            using var crop = new Mat(full, cropRect).Clone();
            Cv2.ImWrite(Path.Combine(options.OutDir, file), crop);

            // 与 Kotlin 侧相同：灰度裁剪图线性放大 4 倍得到 1920x1080 画面
            using var screen = new Mat();
            Cv2.Resize(crop, screen, new Size(1920, 1080), 0, 0, InterpolationFlags.Linear);

            var item = new JsonObject
            {
                ["file"] = file,
                ["cropRect"] = new JsonArray(cropRect.X, cropRect.Y, cropRect.Width, cropRect.Height),
            };

            item["position"] = SafePoint(() => map.GetBigMapPosition(screen));
            // 先验中心：2048 图像坐标，故意偏离 40 个 256 像素
            var expected = new Point2f((cx + 40) * 8f, (cy - 40) * 8f);
            item["expectedCenter"] = new JsonArray(expected.X, expected.Y);
            item["positionWithExpected"] = SafePoint(() => map.GetBigMapPosition(screen, expected));
            try
            {
                var rect = map.GetBigMapRect(screen);
                item["rect"] = new JsonArray(rect.X, rect.Y, rect.Width, rect.Height);
            }
            catch (Exception e)
            {
                item["rect"] = null;
                item["rectError"] = e.GetType().Name;
            }
            array.Add(item);
        }
        return array;
    }

    private static JsonNode? SafePoint(Func<Point2f> action)
    {
        try
        {
            return Point(action());
        }
        catch (Exception e)
        {
            return new JsonObject { ["error"] = e.GetType().Name + ": " + e.Message };
        }
    }

    private static JsonNode? Point(Point2f p)
    {
        return p == default ? null : new JsonArray(p.X, p.Y);
    }

    private static JsonNode? TemplateResult(TeyvatMapTest map, Point2f p)
    {
        if (p == default)
        {
            return null;
        }
        var prev = map.PrevSuccessResult;
        return new JsonObject
        {
            ["x"] = p.X,
            ["y"] = p.Y,
            ["mapX"] = prev.MapPos.X,
            ["mapY"] = prev.MapPos.Y,
            ["confidence"] = prev.Confidence,
            ["layer"] = prev.Layer?.LayerId,
        };
    }
}
