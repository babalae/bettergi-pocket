<#
.SYNOPSIS
  把 BetterGI 编译目录中的地图追踪资源原样复制到 app/src/main/assets/pathing/。

.DESCRIPTION
  只复制地图追踪需要的文件，文件名与内容不做任何修改，并生成 SHA-256 清单 MANIFEST.txt。
  资源体积约 185 MiB，不进 git（见 .gitignore），构建前运行本脚本即可。

  复制内容（来源均相对 BetterGI 编译目录）：
    Assets\Map\Teyvat\*.json                     TemplateMatch 图层描述
    Assets\Map\Teyvat\*_color.webp / *_gray.webp TemplateMatch 粗/精匹配底图
    Assets\Map\Teyvat\Teyvat_0_256_SIFT.*        大地图 SIFT（传送）
    Assets\Map\Teyvat\Teyvat_0_2048_SIFT.*       小地图 SIFT（路线 map_match_method = SIFT）
    GameTask\AutoTrackPath\Assets\tp.json        传送点
    bettergi-scripts-list\repo\pathing\**\*.json 路线 JSON，打进 assets/pathing/routes（不进 git）

.PARAMETER BgiBuildDir
  BetterGI 编译输出目录。
.PARAMETER RouteDir
  地图追踪路线 JSON 根目录。
#>
param(
    [string]$BgiBuildDir = "E:\HuiPrograming\MainProject\better-genshin-impact\BetterGenshinImpact\bin\x64\Debug\net10.0-windows10.0.22621.0",
    [string]$RouteDir = "E:\HuiPrograming\MainProject\bettergi-scripts-list\repo\pathing"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$destRoot = Join-Path $repoRoot "app\src\main\assets\pathing"
$srcTeyvat = Join-Path $BgiBuildDir "Assets\Map\Teyvat"
$srcTp = Join-Path $BgiBuildDir "GameTask\AutoTrackPath\Assets\tp.json"

if (-not (Test-Path $srcTeyvat)) { throw "找不到 BetterGI 地图资源目录：$srcTeyvat" }
if (-not (Test-Path $srcTp)) { throw "找不到 tp.json：$srcTp" }

$destTeyvat = Join-Path $destRoot "Map\Teyvat"
New-Item -ItemType Directory -Force -Path $destTeyvat | Out-Null

$teyvatFiles = @(Get-ChildItem $srcTeyvat -File | Where-Object {
    $_.Extension -eq ".json" -or
    $_.Name -like "*_color.webp" -or
    $_.Name -like "*_gray.webp" -or
    $_.Name -like "*_gray.png" -or
    $_.Name -like "Teyvat_0_256_SIFT.*" -or
    $_.Name -like "Teyvat_0_2048_SIFT.*"
})

foreach ($file in $teyvatFiles) {
    Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $destTeyvat $file.Name) -Force
}
Copy-Item -LiteralPath $srcTp -Destination (Join-Path $destRoot "tp.json") -Force

$routeCount = 0
$indexLines = New-Object System.Collections.Generic.List[string]
if (Test-Path $RouteDir) {
    $RouteDir = (Resolve-Path -LiteralPath $RouteDir).Path.TrimEnd('\', '/')
    $destRoutes = Join-Path $destRoot "routes"
    if (Test-Path -LiteralPath $destRoutes) {
        # 清理旧路线前确认最终路径仍位于本仓库资源目录，拒绝链接或意外路径。
        $resolvedRoutes = (Resolve-Path -LiteralPath $destRoutes).Path
        $expectedRoutes = [IO.Path]::GetFullPath((Join-Path $repoRoot "app\src\main\assets\pathing\routes"))
        if ($resolvedRoutes -ne $expectedRoutes -or
            ((Get-Item -LiteralPath $resolvedRoutes).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "拒绝清理非预期路线目录：$resolvedRoutes"
        }
        Remove-Item -LiteralPath $resolvedRoutes -Recurse -Force
    }
    Get-ChildItem -LiteralPath $RouteDir -Recurse -File -Filter *.json | ForEach-Object {
        $rel = $_.FullName.Substring($RouteDir.Length).TrimStart('\', '/').Replace('\', '/')
        $target = Join-Path $destRoutes ($rel.Replace('/', '\'))
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
        $indexLines.Add($rel)
        $script:routeCount++
    }
    $indexLines.Sort([StringComparer]::Ordinal)
    $indexPath = Join-Path $destRoot "routes-index.txt"
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllLines($indexPath, $indexLines.ToArray(), $utf8)
} else {
    Write-Warning "找不到路线目录：$RouteDir"
}

# 清单：相对路径、字节数、SHA-256，便于核对与来源一致
$manifest = Join-Path $destRoot "MANIFEST.txt"
$lines = @(
    "# 来源：$BgiBuildDir",
    "# 生成时间：$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))",
    "# 相对路径`t字节数`tSHA-256"
)
Get-ChildItem $destRoot -Recurse -File | Where-Object {
    $_.Name -ne "MANIFEST.txt" -and $_.Name -ne "layout.json" -and $_.Name -ne "routes-index.txt" -and $_.FullName -notlike "*\routes\*"
} |
    Sort-Object FullName | ForEach-Object {
        $rel = $_.FullName.Substring($destRoot.Length + 1).Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $lines += "$rel`t$($_.Length)`t$hash"
    }
Set-Content -LiteralPath $manifest -Value $lines -Encoding UTF8

$total = (Get-ChildItem $destRoot -Recurse -File | Measure-Object Length -Sum).Sum
Write-Host ("已复制地图 {0} 个文件、路线 {1} 个，共 {2:N1} MiB -> {3}" -f ($teyvatFiles.Count + 1), $routeCount, ($total / 1MB), $destRoot)
