# Architecture Decision Records

Format: Context → Decision → Alternatives → Consequences. Status values: Proposed, Accepted, Superseded.
The blueprint was approved on 2026-11-17: ADRs 0001–0010 are **Accepted**. ADR-0011 records the lite-mode change made with the approval.

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
| [0011](0011-lite-mode-and-min-sdk-26.md) | Lite mode for 4 GB / 6 GB phones and minSdk 26 (Accepted) |
| [0012](0012-milestone-2-4-implementation-choices.md) | Implementation choices for agents, knowledge, tools, memory and workflows (Accepted) |
| [0013](0013-phone-access-and-daily-digest.md) | Folder sync, phone tools (dialer, SMS) and the daily digest (Accepted) |
| [0014](0014-automatic-digest-email.md) | Opt-in automatic digest email through the user's own SMTP server (Accepted) |
| [0015](0015-voice-vision-documents.md) | Voice, photos (vision models) and Document Studio on the phone (Accepted) |
| [0016](0016-ocr-scam-check-read-aloud.md) | On-device text recognition (Tesseract), Scam check and "Read it to me" (Accepted) |
| [0017](0017-natural-voices-and-barge-in.md) | Natural on-device voices (sherpa-onnx/Piper) and talking over the assistant (Accepted) |
| [0018](0018-automatic-tools-in-chat.md) | Automatic tools in normal chat: on-device skill router + one constrained decision (Accepted) |
