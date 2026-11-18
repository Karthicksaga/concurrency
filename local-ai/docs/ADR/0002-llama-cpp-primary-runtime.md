# ADR-0002: llama.cpp as the primary inference runtime

**Status:** Accepted (2026-11-17)

**Context.** Users expect to run popular open models and import GGUF files. CPU inference must work on every supported device; GPU/NPU acceleration is inconsistent across SoCs. Agents need reliable structured output from small models.

**Decision.** Use llama.cpp (MIT) pinned to a release tag, wrapped by our own C API (`la_*`) and JNI layer. CPU by default; OpenCL on allowlisted Adreno GPUs as opt-in. Use GBNF grammars for structured output. Hide everything behind `InferenceEngine`.

**Alternatives.** ExecuTorch (best NPU support, but per-model `.pte` export, no GGUF); MLC LLM (per-model compile, slower cadence); ONNX Runtime GenAI (Android AAR source build, narrower model set); LiteRT-LM (strong, Apache-2.0, GPU/NPU, but Gemma-centric catalog and own format) — kept as first candidate for a secondary backend.

**Consequences.** Broadest model support and best CPU path. No NPU at MVP. We must track upstream churn (monthly upgrade cadence, contract test suite). Native crash risk handled by crash-loop detection.
