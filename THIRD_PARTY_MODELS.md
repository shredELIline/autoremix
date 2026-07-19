# Third-party model status

No model is bundled or downloaded by the application.

| Capability | Runtime/provider | Status | Reason |
| --- | --- | --- | --- |
| Stem separation | Deterministic HPSS + mid/side | Built-in Tier C | No weights; exact complementary reconstruction |
| Instrument classification | Provider interface | Not selected | License and device benchmark required |
| Music embeddings | Provider interface | Not selected | License and device benchmark required |
| Instrumental continuation | Procedural bridge | Built-in Tier C | Deterministic; vocals excluded |
| Learned quality prediction | Provider interface | Not selected | Technical evaluator remains authoritative |

Candidate runtimes for future measured spikes are LiteRT, ONNX Runtime Mobile,
ExecuTorch, and Core ML. Listing a runtime is not approval of a model or its
weights.
