# On-device ML runtime decision

Research date: 2026-07-19. This document evaluates runtimes, not model weights.

## Runtime comparison

| Runtime | Android | iOS | Useful acceleration | Decision |
| --- | --- | --- | --- | --- |
| [LiteRT](https://ai.google.dev/edge/litert/overview) | Kotlin/C++; CPU/GPU/NPU | C++/Swift; Core ML/Metal delegates | CompiledModel plus legacy Interpreter | First Android model spike |
| [ONNX Runtime Mobile](https://onnxruntime.ai/docs/tutorials/mobile/) | Java/C/C++; CPU/XNNPACK/NNAPI | C/C++/Objective-C; CPU/XNNPACK/Core ML | Reduced-operator custom build | First cross-platform interchange spike |
| [ExecuTorch](https://docs.pytorch.org/executorch/stable/index.html) | AAR or C++; XNNPACK/vendor backends | XCFramework; Core ML/XNNPACK | PyTorch export and delegated `.pte` files | Evaluate for PyTorch-native continuation only |
| [Core ML](https://developer.apple.com/documentation/coreml) | — | Native Apple runtime | CPU/GPU/Neural Engine | Preferred native iOS backend |

Runtime source licenses do not grant permission to redistribute any model,
weights, or training data. Review those artifacts independently.

## Selected approaches

### Stem separation

Default: deterministic complementary HPSS + mid/side Tier C. It exactly
reconstructs the source when stems are summed but is not a neural separator.

Neural provider: not selected. A candidate must support chunked inference,
preserve enough phase coherence for recombination, fit the cache budget, and
pass code/weight/dataset license review plus phone measurements.

### Instrument classification and embeddings

Provider boundaries consume time-window PCM and return versioned labels,
embeddings, confidence, and supported stem families. ONNX Runtime Mobile is the
first cross-platform format spike; LiteRT and Core ML remain valid optimized
platform implementations. No provider is enabled in the release build.

### Instrumental continuation

Default: deterministic phase-aware loop selection, overlap-add, granular
texture, and staged harmonic/tempo adaptation. Generated provenance is allowed
only for non-vocal roles.

Neural provider: not selected. Full-song generation is out of scope. A future
provider may create only short instrumental bridge elements and must obey the
same technical evaluator and deadline cancellation contract.

### Transition quality prediction

Default: deterministic hard gates and weighted diagnostics. A learned predictor
may add a ranking feature but may never override finite-sample, click, peak,
anchor-continuity, or vocal-provenance gates.

## Admission checklist

Before enabling any model:

1. record code, weight, and dataset licenses;
2. record immutable URL, checksum, model card, and schema version;
3. measure package bytes, peak RAM, warm/cold latency, battery, and thermal
   behavior on named devices;
4. validate offline availability and cancellation;
5. run golden, click, corruption, missing-operator, and fallback tests;
6. keep the deterministic provider available.
