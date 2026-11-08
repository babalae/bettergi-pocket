<#
.SYNOPSIS
  验证最终 APK 只含 arm64，并核对完整版/精简版的地图资源和 NCNN。
#>
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][ValidateSet("full", "lite")][string]$Edition
)

$ErrorActionPreference = "Stop"
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
try {
    $names = @($zip.Entries | ForEach-Object FullName)
    $abis = @($names | Where-Object { $_ -match '^lib/[^/]+/[^/]+\.so$' } |
        ForEach-Object { $_.Split('/')[1] } | Sort-Object -Unique)
    if ($abis.Count -ne 1 -or $abis[0] -ne "arm64-v8a") {
        throw "APK 必须只含 arm64-v8a，实际为：$($abis -join ', ')"
    }
    if ($Edition -eq "lite") {
        $forbidden = @($names | Where-Object {
            $_ -match '^assets/(pathing/|recognition/(Pathing|Party)/|licenses/ncnn\.txt$)' -or
            $_ -match '^lib/[^/]+/[^/]*ncnn[^/]*\.so$'
        })
        if ($forbidden.Count) { throw "精简版混入地图追踪资源或 NCNN：$($forbidden -join ', ')" }
    } else {
        $required = @(
            "lib/arm64-v8a/libpocket_ncnn.so",
            "assets/pathing/layout.json", "assets/pathing/tp.json",
            "assets/pathing/routes-index.txt", "assets/pathing/routes-metadata.json",
            "assets/pathing/Map/Teyvat/Teyvat_0_256_SIFT.kp.bin",
            "assets/pathing/Map/Teyvat/Teyvat_0_256_SIFT.mat.png",
            "assets/pathing/Map/Teyvat/Teyvat_0_2048_SIFT.kp.bin",
            "assets/pathing/Map/Teyvat/Teyvat_0_2048_SIFT.mat.png",
            "assets/recognition/Party/avatar_side_classify_sim.bin",
            "assets/recognition/Party/avatar_side_classify_sim.param",
            "assets/recognition/Pathing/Recognition.json"
        )
        foreach ($path in $required) {
            if ($path -notin $names) { throw "完整版缺少必要文件：$path" }
        }
        $routes = @($names | Where-Object { $_.StartsWith("assets/pathing/routes/") -and $_.EndsWith(".json") })
        if (-not $routes.Count) { throw "完整版没有特产路线" }
        if (@($routes | Where-Object { -not $_.StartsWith("assets/pathing/routes/地方特产/") }).Count) {
            throw "完整版包含非特产路线"
        }
        $reader = [IO.StreamReader]::new($zip.GetEntry("assets/pathing/routes-index.txt").Open(), [Text.Encoding]::UTF8)
        try { $index = @($reader.ReadToEnd() -split '\r?\n' | Where-Object { $_.Trim() -and -not $_.StartsWith('#') }) }
        finally { $reader.Dispose() }
        $relativeRoutes = @($routes | ForEach-Object { $_.Substring("assets/pathing/routes/".Length) })
        if (Compare-Object $index $relativeRoutes) { throw "路线索引与 APK 内实际路线不一致" }
        Write-Host "特产路线：$($routes.Count) 条"
    }
    Write-Host ("APK 验证通过：{0}，{1}，{2:N1} MiB" -f $Edition, ($abis -join ', '), ((Get-Item -LiteralPath $ApkPath).Length / 1MB))
} finally { $zip.Dispose() }
