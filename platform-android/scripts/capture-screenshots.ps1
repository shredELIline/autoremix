$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
& (Join-Path $Root 'gradlew.bat') ':platform-android:assembleDebug' ':platform-android:assembleDebugAndroidTest'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$AppApk = Join-Path $Root 'platform-android\build\outputs\apk\debug\platform-android-debug.apk'
$TestApk = Join-Path $Root 'platform-android\build\outputs\apk\androidTest\debug\platform-android-debug-androidTest.apk'
adb install -r $AppApk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
adb install -r $TestApk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
adb shell rm -rf '/sdcard/Android/data/com.alexey.autoremix/files/Pictures'
adb shell am instrument -w -r -e class com.alexey.autoremix.AutoRemixScreenshotTest `
    com.alexey.autoremix.test/androidx.test.runner.AndroidJUnitRunner
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$Destination = Join-Path $Root 'docs\assets\screenshots'
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
adb pull '/sdcard/Android/data/com.alexey.autoremix/files/Pictures/.' $Destination
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$Required = @(
    'library-dark.png', 'now-playing-dark.png', 'transition-dark.png', 'transition-in-progress-dark.png',
    'queue-dark.png', 'analysis-cache-dark.png', 'settings-dark.png',
    'now-playing-light.png'
)
foreach ($Name in $Required) {
    $File = Join-Path $Destination $Name
    if (-not (Test-Path $File) -or (Get-Item $File).Length -eq 0) {
        throw "Missing screenshot: $Name"
    }
}
