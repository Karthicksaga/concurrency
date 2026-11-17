# ADR-0005: Embeddings through llama.cpp with a small GGUF model

**Status:** Accepted (2026-11-17)

**Context.** RAG and agent memory need embeddings on device. Adding ONNX Runtime adds a second native runtime (~8–15 MB) and a second model format.

**Decision.** Run a small embedding model (default proposal: multilingual-e5-small, 384-dim; English alternative: bge-small-en-v1.5) through llama.cpp as a second, small context. Each KB records its embedding model and dimension.

**Alternatives.** ONNX Runtime Mobile (mature, more embedding models available as ONNX); EmbeddingGemma 300M (higher quality, larger, Gemma terms).

**Consequences.** One runtime to maintain. Must verify XLM-R architecture support and numerical parity in Phase 0; ORT is the fallback if verification fails. Changing embedding model requires re-indexing.
