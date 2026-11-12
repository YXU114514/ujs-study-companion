param(
    [string]$JavaRoot = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$GradleCommand = (Join-Path $PSScriptRoot 'gradlew.bat')
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1') -Tasks @('assembleRelease') -JavaRoot $JavaRoot -AndroidSdk $AndroidSdk -GradleCommand $GradleCommand
if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
$JavaRoot = $env:JAVA_HOME
$AndroidSdk = $env:ANDROID_HOME

$keytool = Join-Path $JavaRoot 'bin\keytool.exe'
$signer = Join-Path $AndroidSdk 'build-tools\36.0.0\apksigner.bat'
$aapt = Join-Path $AndroidSdk 'build-tools\36.0.0\aapt.exe'
$unsigned = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
$privateDir = Join-Path $PSScriptRoot '.signing'
$keyFile = Join-Path $privateDir 'ujs-companion.p12'
$credentialsFile = Join-Path $privateDir 'signing.json'
$outputDir = Join-Path $PSScriptRoot 'artifacts'
$apk = Join-Path $outputDir '江大学伴-2.0.1.apk'
$toolApk = Join-Path $outputDir 'UJS-Companion-2.0.1-release.apk'
foreach ($file in @($keytool, $signer, $aapt, $unsigned)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing release build file: $file" }
}
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$hasKey = Test-Path -LiteralPath $keyFile
$hasCredentials = Test-Path -LiteralPath $credentialsFile
if ($hasKey -ne $hasCredentials) {
    throw 'Signing files are incomplete. Restore the matching key and credentials before continuing; existing files have not been replaced.'
}
$previousPassword = [Environment]::GetEnvironmentVariable('UJS_COMPANION_SIGNING_PASSWORD', 'Process')
try {
    if ($hasKey) {
        $credentials = Get-Content -LiteralPath $credentialsFile -Raw | ConvertFrom-Json
        if ([string]::IsNullOrWhiteSpace($credentials.password) -or [string]::IsNullOrWhiteSpace($credentials.alias)) {
            throw 'The local signing credentials are invalid.'
        }
    } else {
        $randomBytes = [byte[]]::new(32)
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
        $credentials = [PSCustomObject]@{ alias = 'ujs-companion'; password = [Convert]::ToBase64String($randomBytes) }
    }
    $env:UJS_COMPANION_SIGNING_PASSWORD = $credentials.password
    if (-not $hasKey) {
        & $keytool -genkeypair -keystore $keyFile -storetype PKCS12 -alias $credentials.alias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=UJS Companion, OU=Personal Android App, O=Local Build, C=CN' -storepass:env UJS_COMPANION_SIGNING_PASSWORD -keypass:env UJS_COMPANION_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Local signing key creation failed.' }
        [System.IO.File]::WriteAllText($credentialsFile, ($credentials | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
    }
    # The Windows aapt binary does not reliably support non-ASCII filenames.
    & $signer sign --ks $keyFile --ks-key-alias $credentials.alias --ks-pass env:UJS_COMPANION_SIGNING_PASSWORD --key-pass env:UJS_COMPANION_SIGNING_PASSWORD --out $toolApk $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $signer verify --verbose --print-certs $toolApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $badging = & $aapt dump badging $toolApk
    if ($LASTEXITCODE -ne 0) { throw 'APK metadata verification failed.' }
    if ($badging -match 'application-debuggable') { throw 'Release APK is unexpectedly debuggable.' }
    Copy-Item -LiteralPath $toolApk -Destination $apk -Force
    Write-Output 'Verified release APK: debuggable is not enabled.'
    Write-Output "APK: $apk"
    Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List
} finally {
    [Environment]::SetEnvironmentVariable('UJS_COMPANION_SIGNING_PASSWORD', $previousPassword, 'Process')
    $credentials = $null
}
