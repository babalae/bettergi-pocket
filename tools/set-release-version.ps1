<#
.SYNOPSIS
  将发布版本写入版本配置；版本变化时递增 Android versionCode。
#>
param(
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Version,
    [string]$VersionFile = (Join-Path (Split-Path -Parent $PSScriptRoot) 'version.properties')
)

$ErrorActionPreference = 'Stop'
$versionName = $Version.Trim()
if ($versionName.StartsWith('v')) { $versionName = $versionName.Substring(1) }
if ($versionName -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\.(0|[1-9][0-9]*))?(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$') {
    throw '请填写有效发布版本号，例如 1.0.1、v1.0.1 或 1.0.1-alpha.1'
}

$content = [IO.File]::ReadAllText([IO.Path]::GetFullPath($VersionFile), [Text.UTF8Encoding]::new($false, $true))
$nameMatches = [regex]::Matches($content, '(?m)^VERSION_NAME=([^\r\n]+)\r?$')
$codeMatches = [regex]::Matches($content, '(?m)^VERSION_CODE=([0-9]+)\r?$')
if ($nameMatches.Count -ne 1 -or $codeMatches.Count -ne 1) {
    throw '版本配置必须各包含一项 VERSION_NAME 和 VERSION_CODE'
}
$versionCode = [long]$codeMatches[0].Groups[1].Value
if ($versionCode -lt 1 -or $versionCode -gt 2100000000) { throw 'VERSION_CODE 超出 Android 版本号范围' }
$changed = $nameMatches[0].Groups[1].Value -cne $versionName
if ($changed) {
    if ($versionCode -eq 2100000000) { throw 'VERSION_CODE 已达到上限，无法递增' }
    $versionCode++
    # 只改版本值，保留文件中的注释和换行格式；重试同一版本时不重复递增。
    $content = [regex]::Replace($content, '(?m)^VERSION_NAME=[^\r\n]+', "VERSION_NAME=$versionName")
    $content = [regex]::Replace($content, '(?m)^VERSION_CODE=[0-9]+', "VERSION_CODE=$versionCode")
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($VersionFile), $content, [Text.UTF8Encoding]::new($false))
}

[pscustomobject]@{
    VersionName = $versionName
    VersionCode = $versionCode
    Tag = "v$versionName"
    Changed = $changed
}
