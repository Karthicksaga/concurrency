# Tech Stack Decision

Status: Draft for approval · Phase 2 output · Research date 2026-11-17

This document records what was evaluated, the evidence used, and the trade-offs behind each choice. Versions and project activity were checked against public release listings in November 2026; items marked **verify in spike** need hands-on confirmation in Phase 0 before they are locked.

## 1. Summary of decisions

| Concern | Decision | Runner-up |
|---|---|---|
| Language / UI | Kotlin 2.x, Jetpack Compose, Material 3 | — |
| Presentation pattern | MVVM with unidirectional state (`StateFlow<UiState>` + intents) | MVI library (rejected: unnecessary dependency) |
| Architecture | Clean-ish layering: UI → domain (use cases only where logic exists) → data/engines | Strict Clean with use case per call (rejected: boilerplate) |
| DI | Hilt | Koin (lighter, but less compile-time safety) |
| Async | Kotlin Coroutines + Flow, structured concurrency for cancellation | RxJava (rejected) |
| LLM runtime | **llama.cpp** via an in-house thin JNI layer | LiteRT-LM (secondary backend candidate), ExecuTorch (NPU future) |
| Embeddings | llama.cpp with a small GGUF embedding model | ONNX Runtime Mobile |
| Relational DB | Room on AndroidX bundled SQLite driver (`app.db`) | SQLDelight |
| Vector + keyword index | Custom native SQLite build with **sqlite-vec** + FTS5 (`knowledge.db`) behind a `VectorIndex` interface | Pure-Kotlin brute force over int8 BLOBs (fallback) |
| Preferences | Jetpack DataStore (Proto) | SharedPreferences |
| Background work | WorkManager (downloads, ingestion); foreground service for long user-initiated runs | — |
| Networking | OkHttp, used by the Downloads module only | Ktor client |
| Serialization | kotlinx.serialization | Moshi |
| Markdown | `compose-markdown`-class renderer evaluated in spike; fallback commonmark-java → AnnotatedString | WebView (rejected: memory, security) |
| PDF text | Platform `PdfRenderer` text APIs where available (**verify in spike**), otherwise PdfBox-Android; parsing runs in an isolated process | PDFium via NDK |
| DOCX | Own minimal parser: ZIP + `XmlPullParser` over `word/document.xml` with size limits | Apache POI (rejected: ~10 MB, heavy) |
| OCR | Deferred to V1.x; Tesseract (Apache-2.0) preferred over ML Kit for privacy posture | ML Kit bundled text recognition |
| Testing | JUnit5, Turbine, MockK, Compose UI test, Macrobenchmark, GoogleTest for native | — |
| Build | Gradle KTS, version catalog, NDK r28+ (16 KB page alignment default), CMake, arm64-v8a only | — |

## 2. Mobile application stack

**Kotlin + Compose + Material 3.** The only first-party, actively developed UI toolkit for new Android apps. Compose suits a streaming UI (token-by-token text, live workflow graphs) because state-driven recomposition maps naturally to `Flow` streams. Material 3 gives accessible defaults (dynamic type, contrast, touch targets) that we restyle with our own tokens rather than using dynamic colour by default (the product has its own dark-first identity; Material You colour can be an option).

Trade-offs:

- Compose `LazyColumn` with very long markdown messages can jank. Mitigation: render markdown off the main thread into an immutable model, stable keys, and chunked rendering for code blocks.
- Streaming at 20+ tok/s can cause excessive recomposition. Mitigation: coalesce tokens into ~50 ms frames in the ViewModel.

**MVVM + Clean-ish layering.** ViewModels expose one `StateFlow<UiState>` and accept intents. Domain use cases exist only where they hold real logic (e.g. `BuildRagContext`, `EstimateModelMemory`, `PlanWorkflow`), not as one-line pass-throughs. This keeps the code navigable for a small team.

**Hilt** over Koin: compile-time graph validation matters because the app has long-lived singletons with native resources (inference engine, vector DB). Hilt's scoping (`@Singleton`, `@ViewModelScoped`) maps cleanly.

**Coroutines + Flow** are mandatory, not optional: cancellation of generation, workflows and ingestion is a core requirement, and structured concurrency gives a cancellation tree for free (cancel the workflow `Job` → steps → JNI abort callback).

## 3. Model inference runtime

### 3.1 Comparison

| Criterion | llama.cpp | ExecuTorch | MLC LLM | ONNX Runtime (GenAI) | LiteRT-LM (added) |
|---|---|---|---|---|---|
| Android integration | NDK/CMake build; we own a thin JNI wrapper. Official Android example exists | Maven AARs (incl. Vulkan and QNN variants); Kotlin LLM APIs since 1.4 | Prebuilt app + Android SDK; per-model TVM compilation | `onnxruntime-android` on Maven; GenAI AAR must be source-built | Kotlin API documented as stable; Play delivery options |
| Model format | **GGUF** (de-facto standard for local models) | `.pte` (export per model via PyTorch) | MLC compiled libraries + weights | ONNX (export/optimise per model) | `.litertlm` |
| GGUF support | Native | No | No | No | No |
| Model breadth | Very broad: Llama, Qwen, Gemma, Phi, Mistral, SmolLM, DeepSeek distills, many embedding models | Curated set with export recipes | Curated set | Curated set (Phi, Llama, Qwen…) | Gemma-family focused, growing |
| CPU inference | Excellent: hand-tuned ARM NEON, i8mm, dotprod kernels; Q4_0 repacking for ARM | Good (XNNPACK) | Fair | Good | Good |
| GPU | OpenCL backend tuned for Adreno 7xx/8xx (Qualcomm-contributed); Vulkan backend (variable on mobile drivers) | Vulkan backend | OpenCL / Vulkan (device-dependent) | Limited on Android | GPU (OpenCL/ML Drift) |
| NPU | None upstream (experimental forks only) | **Qualcomm QNN, MediaTek, Samsung** | No | QNN execution provider | **NPU on supported SoCs** |
| Quantisation | 1.5–8 bit K-quants, I-quants, Q4_0 ARM repack, MXFP4; quantise on desktop | 4/8-bit, 2-bit weights (1.4) | 3/4-bit group quant | INT4 / INT8 | INT4 / INT8 per model |
| Memory | mmap weights (page-cache backed, low PSS), quantised KV cache (q8_0) | Loads into memory; NPU paths need contiguous buffers | Moderate | Moderate | Good |
| Structured output | **GBNF grammars / JSON schema constrained sampling** built in | Manual | JSON mode limited | Constrained decoding in GenAI (partial) | Function-calling helpers for Gemma |
| Embeddings | Yes (BERT, nomic-bert, XLM-R, Gemma embedding, …) | Possible, separate export | No | Yes (core ORT) | Not primary use |
| Maintenance | Very active (multiple releases per day, ggml-org) | Active (Meta; 1.0 Oct 2025, 1.4 in 2026) | Slower recent cadence; third-party trackers report long gaps between releases | Active (GenAI 0.17.x Sep 2026) | Active (Google; open source 2026) |
| Licence | MIT | BSD-3-Clause | Apache-2.0 | MIT | Apache-2.0 |

### 3.2 Recommendation: llama.cpp as the primary runtime

1. **The format is the product.** Users expect to bring "any GGUF from Hugging Face". Only llama.cpp loads GGUF natively, and essentially every open model gets a GGUF quantisation within days of release. Every other runtime requires a per-model export pipeline we would have to operate.
2. **Best CPU path on the widest set of devices.** CPU inference is the only path guaranteed on every 8 GB phone. llama.cpp's ARM kernels are the most optimised open implementation.
3. **Grammar-constrained sampling** is the foundation of reliable tool calls and plans on small models (3–8B). We can force valid JSON matching each tool's schema instead of hoping the model complies.
4. **mmap + quantised KV cache** keep resident memory predictable and let Android reclaim weight pages under pressure without killing us.
5. **One native dependency** covers both generation and embeddings.
6. **MIT licence**, no field-of-use restrictions.

Risks and mitigations:

| Risk | Mitigation |
|---|---|
| API churn (daily releases, breaking C API changes) | Pin a release tag; upgrade monthly behind our own C wrapper (`la_*` functions) so Kotlin never sees llama.cpp types |
| No NPU support | `InferenceEngine` interface allows a second backend (LiteRT-LM or ExecuTorch-QNN) per model later |
| GPU backends inconsistent across vendors | CPU default; OpenCL opt-in only on allowlisted Adreno GPUs after an on-device self-test |
| Native crashes take down the app | Inference in the main process for latency, but strict input validation and a crash-loop detector that disables GPU/model on repeated native crashes. Process isolation evaluated in spike (cost: IPC for tokens is cheap, but shared memory for embeddings needs work) |

### 3.3 Why not the others (as primary)

- **ExecuTorch** — strongest NPU story (QNN, MediaTek) and good for a few flagship models, but each model needs a PyTorch export to `.pte`, so the "import any model" promise breaks. Keep as a future accelerated backend for curated models.
- **MLC LLM** — impressive GPU results, but per-model compilation and a slower recent release cadence make it a maintenance risk.
- **ONNX Runtime GenAI** — solid on Windows/Qualcomm, but the Android GenAI package still needs a source build and model coverage is narrower. Core ORT remains a valid embeddings fallback.
- **LiteRT-LM** — the strongest alternative today: Google-backed, Apache-2.0, stable Kotlin API, GPU/NPU. Limited model catalogue and a format tied to Google's conversion tooling. Recommended as the **first secondary backend** to evaluate in V1.x for Gemma-class models where NPU/GPU gains are large.

## 4. Knowledge engine

### 4.1 Vector storage

| Option | Notes | Verdict |
|---|---|---|
| **sqlite-vec** (asg017) | Pure C SQLite extension, `vec0` virtual table, float32/int8/bit vectors, KNN via brute force with SIMD, metadata/partition columns. MIT/Apache-2.0. Pre-1.0 (v0.1.x), small maintainer team | **Choose**, behind an interface |
| Android framework SQLite | Cannot load extensions | Not usable for vectors |
| AndroidX bundled SQLite driver | Bundles its own SQLite with FTS5; no supported extension loading | Use for Room (`app.db`) |
| sqliteai `sqlite-vector` | Has an Android Maven package, but Elastic License 2.0 (source-available) | Rejected on licence |
| ObjectBox (HNSW) | Mature Android DB with vector search; proprietary core, adds a second DB paradigm | Rejected: licence + lock-in |
| Pure Kotlin brute force | int8 vectors in BLOBs, scan with SIMD-ish loops | Fallback implementation |
| HNSW libraries (hnswlib via JNI, usearch) | Faster at >200k vectors; more RAM, index rebuild complexity | V2 if corpora outgrow brute force |

Why brute force is acceptable: a heavy personal corpus is ~2,000 documents ≈ 50,000 chunks. 50k × 384-dim int8 = 19 MB, scanned in well under 100 ms on modern ARM cores. sqlite-vec also supports binary quantisation for a coarse pass followed by int8/float rescoring.

**Integration approach:** compile the SQLite amalgamation with FTS5 and sqlite-vec statically into our native library (same CMake project as llama.cpp), exposed through a small Kotlin `KnowledgeStore` wrapper. Knowledge data lives in its own database file (`knowledge.db`). Room keeps relational app data in `app.db`. See ADR-0004.

### 4.2 Embedding model

| Option | Size (approx.) | Dim | Languages | Licence | Notes |
|---|---|---|---|---|---|
| bge-small-en-v1.5 | ~67 MB F16 | 384 | English | MIT | Fast, strong English baseline |
| **multilingual-e5-small** | ~120 MB Q8 | 384 | ~100 | MIT | **Proposed default**: users are unlikely to be English-only. XLM-R arch support in llama.cpp: **verify in spike** |
| nomic-embed-text v1.5 | ~140 MB Q8 | 768 (Matryoshka) | English | Apache-2.0 | Long context (8k), good quality |
| EmbeddingGemma 300M | ~330 MB Q8 | 768 (Matryoshka to 128) | 100+ | Gemma terms | Best quality/size in class; licence terms need review |

Embeddings run through llama.cpp as a second, small model context. The embedding model ID and dimension are stored per knowledge base; changing it requires re-indexing (the UI says so).

ONNX Runtime Mobile is the fallback if a desired embedding model has no reliable GGUF path. It adds ~8–15 MB to the APK.

### 4.3 Parsing

| Format | Approach | Risk |
|---|---|---|
| TXT / MD / code | Charset detection (UTF-8 first, fallback ICU), size cap 20 MB | Low |
| PDF | Platform `PdfRenderer` text extraction where the API level supports it (**verify in spike**); otherwise PdfBox-Android (Apache-2.0; maintenance moderate). Isolated process | Medium: malformed PDFs, CPU spikes |
| DOCX | ZIP + XmlPullParser, entry-size and ratio limits (zip-bomb defence) | Low |
| Scanned PDF / images | OCR in V1.x via Tesseract | Medium: quality and speed |

## 5. Agent orchestration

There is no server-side framework suitable for Android: LangChain/LangGraph, CrewAI and AutoGen are Python/JS-first, assume large-context hosted models and parallel calls, and would require embedding an interpreter. We build a small Kotlin engine:

- Supervisor-planned **DAG**, executed by a scheduler that is **logically parallel but physically serial** on the single loaded model; tool calls (non-LLM) can overlap LLM work. This is the hybrid model. See `AGENT_ARCHITECTURE.md`.
- Plans and tool calls are generated under **GBNF grammars** derived from JSON Schemas.
- Prompt-prefix caching per agent using llama.cpp sequence state save/restore.

## 6. Build and platform requirements

- **ABI:** arm64-v8a only. x86_64 builds for emulator/CI only.
- **16 KB page size:** Google Play requires 16 KB-aligned native libraries for apps targeting Android 15+. NDK r28+ aligns by default; CI checks alignment with `zipalign -c -P 16`.
- **minSdk 31 proposed** (Android 12): devices with ≥8 GB RAM are overwhelmingly on 12+; reduces test matrix; gives `PerformanceHintManager` and better foreground service rules. Approval needed.
- **targetSdk:** latest stable at implementation time.
- **APK size budget:** < 40 MB (native libs ~15 MB with CPU + OpenCL backends).

## 7. Dependencies explicitly not chosen

| Library | Why not |
|---|---|
| LangChain4j | JVM server orientation, heavy reflection, assumes hosted APIs |
| Apache POI | Size and method count for DOCX we can parse in 200 lines |
| Realm | Deprecated by MongoDB |
| Firebase / any analytics SDK | Conflicts with privacy promise |
| WebView-based chat rendering | Memory, attack surface |

## 8. Sources consulted

- llama.cpp OpenCL backend docs and Qualcomm's Adreno backend announcement (Nov 2024; supported on Adreno 750/830, best with Q4_0).
- ExecuTorch 1.0 announcement (Oct 2025) and 1.3/1.4 release notes (Vulkan cooperative matrix, QNN updates, Kotlin LLM APIs).
- ONNX Runtime GenAI package listings (0.17.1, Sep 2026) and Arm learning path (Android AAR built from source).
- LiteRT-LM documentation and Google Developers Blog (2026).
- MLC LLM third-party reviews (maintenance cadence; OpenCL reliability varies).
- AndroidX SQLite release notes (2.7.x, 2026); sqlite-vector licence (Elastic 2.0).

All of these must be re-checked when Phase 0 starts; this space moves monthly.
