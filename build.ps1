param(
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest'),
    [string]$JavaRoot = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$GradleCommand = (Join-Path $PSScriptRoot 'gradlew.bat')
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$localConfigPath = Join-Path $PSScriptRoot '.build.local.json'
$localConfig = if (Test-Path -LiteralPath $localConfigPath) { Get-Content -LiteralPath $localConfigPath -Raw | ConvertFrom-Json } else { $null }
if ([string]::IsNullOrWhiteSpace($JavaRoot) -and $localConfig) { $JavaRoot = $localConfig.javaRoot }
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($AndroidSdk) -and $localConfig) { $AndroidSdk = $localConfig.androidSdk }
if ([string]::IsNullOrWhiteSpace($JavaRoot) -or [string]::IsNullOrWhiteSpace($AndroidSdk)) {
    throw 'Configure JAVA_HOME (JDK 17) and ANDROID_HOME, or pass -JavaRoot and -AndroidSdk.'
}
foreach ($tool in @((Join-Path $JavaRoot 'bin\java.exe'), (Join-Path $AndroidSdk 'platforms\android-36\android.jar'), $GradleCommand)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required build tool is missing: $tool" }
}
$env:JAVA_HOME = $JavaRoot
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:PATH = (Join-Path $JavaRoot 'bin') + ';' + $env:PATH
$sdkProperty = 'sdk.dir=' + $AndroidSdk.Replace('\', '/').Replace(':', '\:')
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), $sdkProperty + "`n", [System.Text.UTF8Encoding]::new($false))
& $GradleCommand --no-daemon --console=plain @Tasks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if ((Test-Path -LiteralPath $apk) -and ($Tasks -contains 'assembleDebug' -or $Tasks -contains ':app:assembleDebug')) {
    $outputDir = Join-Path $PSScriptRoot 'artifacts'
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $destination = Join-Path $outputDir 'UJS-Companion-2.0.1-debug.apk'
    Copy-Item -LiteralPath $apk -Destination $destination -Force
    Write-Output "APK: $destination"
    Get-FileHash -LiteralPath $destination -Algorithm SHA256 | Format-List
}
