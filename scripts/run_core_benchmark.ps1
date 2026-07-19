param(
    [string]$BuildDirectory = "build/audio-core-benchmark"
)

$ErrorActionPreference = "Stop"

cmake -S audio-core -B $BuildDirectory -G Ninja `
    -DCMAKE_BUILD_TYPE=Release `
    -DAUTOREMIX_AUDIO_CORE_BUILD_TESTS=OFF `
    -DAUTOREMIX_AUDIO_CORE_BUILD_BENCHMARKS=ON
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

cmake --build $BuildDirectory --config Release --parallel
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$executable = Join-Path $BuildDirectory "autoremix_audio_core_benchmark"
if ($env:OS -eq "Windows_NT") {
    $executable = "$executable.exe"
}
& $executable
exit $LASTEXITCODE
