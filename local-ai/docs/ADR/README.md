# Architecture Decision Records

Format: Context → Decision → Alternatives → Consequences. Status values: Proposed, Accepted, Superseded.
All ADRs below are **Proposed** until you approve the blueprint.

| ADR | Title |
|---|---|
| [0001](0001-native-kotlin-compose.md) | Native Android with Kotlin, Compose and Material 3 |
| [0002](0002-llama-cpp-primary-runtime.md) | llama.cpp as the primary inference runtime |
| [0003](0003-single-shared-model-for-agents.md) | Logical agents share one loaded model |
| [0004](0004-sqlite-vec-knowledge-store.md) | Knowledge store: custom SQLite with sqlite-vec + FTS5 |
| [0005](0005-embeddings-via-llama-cpp.md) | Embeddings through llama.cpp with a small GGUF model |
| [0006](0006-custom-kotlin-orchestrator.md) | Custom Kotlin supervisor orchestrator with grammar-constrained JSON |
| [0007](0007-typed-tools-no-code-execution.md) | Typed tools only; no model-generated code execution |
| [0008](0008-offline-first-network-boundary.md) | Offline-first with a single network boundary |
| [0009](0009-memory-separate-from-knowledge.md) | Agent memory is separate from knowledge, approval by default |
| [0010](0010-isolated-web-prototype.md) | Dependency-free web prototype, isolated from the Android codebase |
