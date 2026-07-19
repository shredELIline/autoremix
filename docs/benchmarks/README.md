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

## Recorded host runs

- [`2026-07-19-aab3136-docker-wsl2-x86_64.json`](2026-07-19-aab3136-docker-wsl2-x86_64.json) — progressive non-repeating core;
- [`2026-07-19-docker-wsl2-x86_64.json`](2026-07-19-docker-wsl2-x86_64.json) — earlier shared-core baseline.
