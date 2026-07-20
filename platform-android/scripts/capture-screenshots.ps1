$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$JavaCandidates = @(
    $env:JAVA_HOME,
    (Join-Path $env:USERPROFILE 'scoop\apps\temurin17-jdk\current'),
    (Join-Path $env:USERPROFILE '.jdks\temurin-17')
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) }
if (-not $env:JAVA_HOME -and $JavaCandidates) {
    $env:JAVA_HOME = $JavaCandidates | Select-Object -First 1
}
$SdkCandidates = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'platform-tools\adb.exe')) }
if (-not $env:ANDROID_SDK_ROOT -and $SdkCandidates) {
    $env:ANDROID_SDK_ROOT = $SdkCandidates | Select-Object -First 1
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (-not $env:ANDROID_SDK_ROOT) { throw 'Android SDK not found' }
$Adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
if (-not (Test-Path $Adb)) { throw 'Android SDK platform-tools not found' }
& (Join-Path $Root 'gradlew.bat') ':platform-android:assembleDebug' ':platform-android:assembleDebugAndroidTest'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$AppApk = Join-Path $Root 'platform-android\build\outputs\apk\debug\platform-android-debug.apk'
$TestApk = Join-Path $Root 'platform-android\build\outputs\apk\androidTest\debug\platform-android-debug-androidTest.apk'
& $Adb install -r $AppApk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Adb install -r $TestApk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Adb shell rm -rf '/sdcard/Android/data/com.alexey.autoremix/files/Pictures'
& $Adb shell am instrument -w -r -e class com.alexey.autoremix.AutoRemixScreenshotTest `
    com.alexey.autoremix.test/androidx.test.runner.AndroidJUnitRunner
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$Destination = Join-Path $Root 'docs\assets\screenshots'
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
& $Adb pull '/sdcard/Android/data/com.alexey.autoremix/files/Pictures/.' $Destination
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$Required = @(
    'timeline-normal-00-15-dark.png',
    'timeline-entry-01-20-dark.png',
    'timeline-planned-transition-dark.png',
    'transition-preparing-dark.png',
    'transition-guitar-anchor-dark.png',
    'transition-bass-handoff-dark.png',
    'transition-key-change-dark.png',
    'transition-vocal-handoff-dark.png',
    'timeline-landed-01-14-dark.png',
    'transition-reduced-motion-dark.png'
)
foreach ($Name in $Required) {
    $File = Join-Path $Destination $Name
    if (-not (Test-Path $File) -or (Get-Item $File).Length -eq 0) {
        throw "Missing screenshot: $Name"
    }
}
