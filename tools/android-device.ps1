[CmdletBinding()]
param(
    [ValidateSet('doctor','build','deploy','launch','game','permissions','capture','tap','a11y-tap','a11y-status','swipe','key','logs','status')]
    [string]$Action = 'doctor',
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$GamePackage,
    [int]$X, [int]$Y, [int]$X2, [int]$Y2,
    [int]$Duration = 300,
    [string]$KeyCode = 'KEYCODE_BACK',
    [switch]$SkipBuild,
    [switch]$IncludeUiDump
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$package = 'com.bettergi.pocket'
$component = "$package/.input.InputAccessibilityService"
if (!$Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (!$Sdk -and (Test-Path "$root/local.properties")) {
    $line = Get-Content "$root/local.properties" | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $Sdk = $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
}
if (!$Sdk) { $Sdk = "$env:LOCALAPPDATA/Android/Sdk" }
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
if (!(Test-Path $adb)) { throw "ADB missing: $adb" }
function AdbCall {
    param([string[]]$Arguments)
    $prefix = @()
    if ($Serial) { $prefix = @('-s', $Serial) }
    # Windows PowerShell treats successful adb progress on stderr as ErrorRecord.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = & $adb @prefix @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($exitCode -ne 0) { throw "adb failed ($exitCode): $result" }
    return @($result | ForEach-Object { "$_" })
}
function RequireDevice {
    $lines = & $adb devices
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
    $devices = @($lines | Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline)\b' })
    if (!$Serial) {
        if ($devices.Count -ne 1) { throw "Specify -Serial; connected devices: $($devices -join ', ')" }
        $script:Serial = ($devices[0] -split '\s+')[0]
    }
    $state = ($devices | Where-Object { ($_ -split '\s+')[0] -eq $Serial }) -join ''
    if ($state -match '\sunauthorized\b') { throw 'Unlock tablet and accept the USB debugging RSA prompt (Always allow).' }
    if ($state -notmatch '\sdevice\b') { throw "Device is not ready: $Serial ($state)" }
}
function BuildApp {
    # Prefer Studio's runtime / cached JDK 21 over the system Java 8.
    $candidates = @($env:JAVA_HOME, 'E:/Program Files/Android/Android Studio/jbr', 'C:/Program Files/Android/Android Studio/jbr')
    $candidates += @(Get-ChildItem "$env:USERPROFILE/.gradle/jdks" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    $javaHome = $candidates | Where-Object { $_ -and (Test-Path "$_/bin/java.exe") -and $_ -notmatch '1\.8|corretto-1' } | Select-Object -First 1
    if (!$javaHome) { throw 'JDK 17+ required; set JAVA_HOME to Android Studio jbr or JDK 21.' }
    $oldJava = $env:JAVA_HOME
    Push-Location $root
    try {
        $env:JAVA_HOME = $javaHome
        & "$root/gradlew.bat" :app:assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
    } finally { $env:JAVA_HOME = $oldJava; Pop-Location }
}
function LaunchApp([string]$target) {
    $activity = (AdbCall @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',$target) | Select-Object -Last 1).Trim()
    if ($activity -notmatch '^[\w.]+/[\w.$]+$') { throw "No launcher activity for $target : $activity" }
    AdbCall @('shell','am','start','-W','-n',$activity)
}
function ArtifactDir {
    $dir = Join-Path $root ("artifacts/device/{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'), $Serial)
    New-Item -ItemType Directory -Force $dir | Out-Null
    return $dir
}
if ($Action -eq 'build') { BuildApp; return }
if ($Action -eq 'doctor') {
    "SDK: $Sdk"
    "JAVA_HOME: $env:JAVA_HOME (build auto-detects Studio JBR / cached JDK)"
    AdbCall @('version')
    AdbCall @('devices','-l')
    RequireDevice
    AdbCall @('shell','getprop','ro.product.model')
    AdbCall @('shell','getprop','ro.build.version.release')
    AdbCall @('shell','getprop','ro.product.cpu.abilist')
    AdbCall @('shell','wm','size')
    return
}
RequireDevice
switch ($Action) {
    'deploy' {
        if (!$SkipBuild) { BuildApp }
        $abis = ((AdbCall @('shell','getprop','ro.product.cpu.abilist')) -join '').Trim().Split(',')
        $metadata = Get-Content "$root/app/build/outputs/apk/debug/output-metadata.json" -Raw | ConvertFrom-Json
        $apk = $null
        foreach ($abi in $abis) {
            $entry = $metadata.elements | Where-Object { @($_.filters | Where-Object { $_.filterType -eq 'ABI' -and $_.value -eq $abi }).Count -gt 0 } | Select-Object -First 1
            if ($entry) { $apk = Join-Path "$root/app/build/outputs/apk/debug" $entry.outputFile; break }
        }
        # 仅构建 arm64 后，Gradle 的单 APK 元数据不再包含 ABI split filter。
        if (!$apk -and 'arm64-v8a' -in $abis) {
            $entry = $metadata.elements | Where-Object { $_.type -eq 'SINGLE' -and @($_.filters).Count -eq 0 } | Select-Object -First 1
            if ($entry) { $apk = Join-Path "$root/app/build/outputs/apk/debug" $entry.outputFile }
        }
        if (!$apk) { throw "No APK matches device ABI: $abis" }
        AdbCall @('install','-r',$apk)
        AdbCall @('shell','appops','set',$package,'SYSTEM_ALERT_WINDOW','allow')
        $api = [int]((AdbCall @('shell','getprop','ro.build.version.sdk')) -join '')
        if ($api -ge 33) { AdbCall @('shell','pm','grant',$package,'android.permission.POST_NOTIFICATIONS') }
        LaunchApp $package
    }
    'launch' { LaunchApp $package }
    'game' {
        if (!$GamePackage) {
            $installed = @(AdbCall @('shell','pm','list','packages') | ForEach-Object { "$_".Replace('package:','').Trim() })
            $games = @('com.miHoYo.Yuanshen','com.miHoYo.ys.bilibili','com.miHoYo.GenshinImpact','com.miHoYo.cloudgames.ys','com.miHoYo.cloudgames.GenshinImpact','com.miHoYo.ys.mi') | Where-Object { $_ -in $installed }
            if (@($games).Count -ne 1) { throw "Specify -GamePackage. Detected: $games" }
            $GamePackage = @($games)[0]
        }
        LaunchApp $GamePackage
    }
    'permissions' {
        # Preserve other accessibility services already enabled on the user's tablet.
        $existing = ((AdbCall @('shell','settings','get','secure','enabled_accessibility_services')) -join '').Trim()
        $services = @($existing.Split(':') | Where-Object { $_ -and $_ -ne 'null' })
        if ($component -notin $services -and "$package/$package.input.InputAccessibilityService" -notin $services) {
            $services += $component
            AdbCall @('shell','settings','put','secure','enabled_accessibility_services',($services -join ':'))
        }
        AdbCall @('shell','settings','put','secure','accessibility_enabled','1')
        AdbCall @('shell','appops','set',$package,'SYSTEM_ALERT_WINDOW','allow')
        AdbCall @('shell','dumpsys','accessibility')
    }
    'capture' {
        $dir = ArtifactDir
        # Pull a device-side PNG: PowerShell 5 output redirection corrupts binary exec-out data.
        $remote = "/data/local/tmp/bgi-$([guid]::NewGuid().ToString('N')).png"
        try {
            AdbCall @('shell','screencap','-p',$remote)
            AdbCall @('pull',$remote,"$dir/screen.png")
        } finally { AdbCall @('shell','rm','-f',$remote) | Out-Null }
        # UIAutomator temporarily suppresses accessibility services on this tablet.
        # Keep ordinary screenshots nonintrusive; only request UI XML explicitly.
        if ($IncludeUiDump) {
            Write-Warning 'UIAutomator may reconnect accessibility services and reset foreground detection. Switch Home -> game afterwards.'
            AdbCall @('shell','uiautomator','dump','/data/local/tmp/bgi-window.xml') | Set-Content "$dir/ui-dump-status.txt" -Encoding UTF8
            AdbCall @('pull','/data/local/tmp/bgi-window.xml',"$dir/window.xml")
        }
        AdbCall @('shell','dumpsys','activity','activities') | Set-Content "$dir/activities.txt" -Encoding UTF8
        AdbCall @('shell','dumpsys','accessibility') | Set-Content "$dir/accessibility.txt" -Encoding UTF8
        AdbCall @('shell','dumpsys','media_projection') | Set-Content "$dir/media-projection.txt" -Encoding UTF8
        AdbCall @('logcat','-d','-t','2000','-v','threadtime') | Set-Content "$dir/logcat.txt" -Encoding UTF8
        "Artifacts: $dir"
    }
    'tap' { AdbCall @('shell','input','tap',"$X","$Y") }
    'a11y-tap' {
        $result = AdbCall @('shell','am','broadcast','--include-stopped-packages','-n',"$package/.debug.DeviceAutomationReceiver",'-a',"$package.debug.TAP",'--ei','x',"$X",'--ei','y',"$Y",'--ei','duration',"$Duration")
        $result
        if (($result -join "`n") -notmatch 'gestureAccepted=true') { throw 'Accessibility gesture rejected; install debug APK and check a11y-status.' }
    }
    'a11y-status' {
        $result = AdbCall @('shell','am','broadcast','--include-stopped-packages','-n',"$package/.debug.DeviceAutomationReceiver",'-a',"$package.debug.STATUS")
        $result
        if (($result -join "`n") -notmatch 'connected=true') { throw 'Accessibility service not connected or debug receiver missing.' }
    }
    'swipe' { AdbCall @('shell','input','swipe',"$X","$Y","$X2","$Y2","$Duration") }
    'key' { AdbCall @('shell','input','keyevent',$KeyCode) }
    'logs' { AdbCall @('logcat','-d','-t','1000','-v','threadtime') }
    'status' {
        AdbCall @('shell','settings','get','secure','enabled_accessibility_services')
        AdbCall @('shell','dumpsys','accessibility')
        AdbCall @('shell','dumpsys','activity','services',$package)
        AdbCall @('shell','dumpsys','media_projection')
    }
}
