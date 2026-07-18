# AutoRemix 2.0 quality report

## Build
- package: `com.alexey.autoremix`
- version: `2.0.0` (`versionCode=8`)
- minSdk: 29 (Android 10)
- targetSdk: 35
- APK Signature Scheme v3 verified
- signing certificate matches the 1.5 build, so in-place update is supported

## Runtime architecture checks
- no `android.media.MediaPlayer` import remains in the runtime source;
- one `AudioTrack` receives the already-rendered PCM master;
- only one future scene is queued to reduce peak memory pressure;
- automatic `LayerPlan.Type` contains continuity-preserving role narratives only.

## Automated checks
- exact complementary-stem reconstruction: relative error below `1e-5`;
- all 9 layer narratives begin on A and finish on their declared A/B anchor;
- 100,000 random director inputs: every accepted plan respected vibe and tempo invariants and no
  cut/slam/snap narrative appeared;
- onset phase alignment recovers a synthetic 137 ms beat offset;
- WSOLA duration and splice safety checks at 0.92x, 0.96x, 1.04x, and 1.08x;
- master bus finite-sample, edge-fade, and true-peak ceiling checks.

## Honest limits
The current APK uses deterministic HPSS + center/side source separation, not a bundled neural Demucs
model. The APK has been compiled and statically tested here, but final musical and thermal performance
must be evaluated on a real Android phone and real music files.
