<#
.SYNOPSIS
  为 GitHub Actions 准备未提交到 Git 的地图资源，再调用与本地相同的同步脚本。
.PARAMETER RouteDir
  bettergi-scripts-list 的 release 分支中的 repo/pathing 目录。
.PARAMETER MapPackagePath
  可选的本地 NuGet 包路径，用于离线复核；仍会校验 SHA-256。
#>
param(
    [Parameter(Mandatory = $true)][string]$RouteDir,
    [string]$MapPackagePath = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$workDir = Join-Path $repoRoot "build/ci-pathing"
New-Item -ItemType Directory -Force -Path $workDir | Out-Null

# 与 BetterGI 的 BetterGenshinImpact.csproj 对齐，升级包版本时同步更新校验值。
$mapVersion = "1.0.26"
$mapSha256 = "bfe9fa05e5817e2d92510a5bcb5443adab710c02fa4ef886c18df9563559b833"
$bgiCommit = "4b550f1aa824b4f17877c0e7d84a1d21545b1489"
if (-not $MapPackagePath) {
    $MapPackagePath = Join-Path $workDir "bettergi.assets.map.$mapVersion.nupkg"
    $url = "https://api.nuget.org/v3-flatcontainer/bettergi.assets.map/$mapVersion/bettergi.assets.map.$mapVersion.nupkg"
    Invoke-WebRequest -Uri $url -OutFile $MapPackagePath
}
if ((Get-FileHash -LiteralPath $MapPackagePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $mapSha256) {
    throw "BetterGI 地图资源包 SHA-256 不匹配"
}
$packageDir = Join-Path $workDir "map-package"
[IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath($MapPackagePath), $packageDir, $true)

# NuGet contentFiles 与 BetterGI 编译目录的 Assets 布局一致。
$bgiBuildDir = Join-Path $packageDir "contentFiles/any/any"
$tpDir = Join-Path $bgiBuildDir "GameTask/AutoTrackPath/Assets"
New-Item -ItemType Directory -Force -Path $tpDir | Out-Null
$tpUrl = "https://raw.githubusercontent.com/babalae/better-genshin-impact/$bgiCommit/BetterGenshinImpact/GameTask/AutoTrackPath/Assets/tp.json"
Invoke-WebRequest -Uri $tpUrl -OutFile (Join-Path $tpDir "tp.json")

& (Join-Path $PSScriptRoot "sync-pathing-assets.ps1") -BgiBuildDir $bgiBuildDir -RouteDir $RouteDir
