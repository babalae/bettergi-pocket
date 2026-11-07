# 把同目录下的 mock-*.svg 渲染成 2x PNG（1600×720），供设计文档引用。
# 用法：powershell -File docs/images/ui/render.ps1 [名称...]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path $edge)) { $edge = "$env:ProgramFiles\Google\Chrome\Application\chrome.exe" }
$dir = $PSScriptRoot
if (-not $Names) { $Names = Get-ChildItem $dir -Filter 'mock-*.svg' | ForEach-Object { $_.BaseName } }
foreach ($name in $Names) {
    $svg = [IO.File]::ReadAllText((Join-Path $dir "$name.svg"))
    $html = Join-Path $env:TEMP "$name.html"
    $page = "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;padding:0;overflow:hidden}</style></head><body>$svg</body></html>"
    [IO.File]::WriteAllText($html, $page, [Text.Encoding]::UTF8)
    $out = Join-Path $dir "$name.png"
    $url = 'file:///' + ($html -replace '\\', '/')
    # Edge 会往 stderr 写无关日志，重定向到临时文件，避免被 PowerShell 当成错误。
    $errLog = [IO.Path]::Combine($env:TEMP, $name + '-render.log')
    $edgeArgs = @('--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=2',
        '--window-size=800,360', ('"--screenshot=' + $out + '"'), $url)
    Remove-Item $out -ErrorAction SilentlyContinue
    Start-Process -FilePath $edge -ArgumentList $edgeArgs -Wait -NoNewWindow -RedirectStandardError $errLog
    Write-Output ("{0} {1} bytes" -f $out, (Get-Item $out).Length)
}
