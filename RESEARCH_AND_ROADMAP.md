# Research basis and neural roadmap

## Why 2.0 uses a shared PCM master

Separate high-level media players have independent clocks and vendor implementations. AutoRemix 2.0
therefore decodes ahead and performs every layer gain inside one sample timeline before one AudioTrack
output. This removes start/mute/release commands from the audible transition path.

## Why stems change the product

A coherent remix is not a crossfade between two mastered songs. It is a role exchange. The engine
needs separately controllable lead, drums, bass, and backing so one anchor can stay audible while the
arrangement underneath it changes. The current separator combines harmonic/percussive masking and
stereo center/side information while keeping the four outputs complementary.

## Neural upgrade path

A production neural option should implement the same StemProvider contract and return the same four
roles. Candidate runtimes are LiteRT or ONNX Runtime Mobile. A compact quantized/streaming separator
is preferable to shipping a desktop-sized Hybrid Transformer Demucs model directly in the APK.
The model must be benchmarked on representative Android devices for RAM, thermal throttling, latency,
and stem leakage before becoming the default.

## Further product work

- device-specific performance profiling and adaptive render quality;
- persistent analysis metadata so the second session starts immediately;
- richer phrase/downbeat segmentation and chorus/verse structure;
- user feedback learning for preferred energy arcs and layer narratives;
- an authorised streaming-source interface for a later Yandex Music integration;
- optional export only after explicit user action and licensing review.
