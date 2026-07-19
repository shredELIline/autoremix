# Benchmarks

Do not compare results across unnamed machines or build types. Each committed report must include:

- commit;
- UTC date;
- CPU/device and OS;
- compiler and version;
- Release/Debug and sanitizer state;
- sample rate, channels, duration, iteration count;
- median render time and realtime factor;
- output peak and selected fallback stage.

Run on Linux/macOS:

```bash
./scripts/run_core_benchmark.sh
```

Run on Windows:

```powershell
./scripts/run_core_benchmark.ps1
```

Phone CPU, RAM, battery, thermal, inference, and underrun results are not available until measured on named devices.
