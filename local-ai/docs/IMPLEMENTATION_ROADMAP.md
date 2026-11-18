# Implementation Roadmap

Status: Draft for approval · Assumes a core team of 2 Android engineers, 1 native/ML engineer, 1 designer (part-time), 1 QA (from M2)

## Phase 0 — Technical spikes (3 weeks) · gate for everything else

| Spike | Exit criterion |
|---|---|
| llama.cpp JNI build (NDK r28+, 16 KB aligned, arm64 variants) | Qwen3-4B Q4_K_M generates ≥ 8 tok/s on the 8 GB reference device; APK native size ≤ 20 MB |
| Grammar-constrained JSON | ≥ 99 % valid plans over 200 prompts with 3–4B models; speed cost measured |
| Prefix KV save/restore | Agent switch ≤ 500 ms with cached prefix |
| sqlite-vec + FTS5 custom SQLite build | 50k chunks: top-24 KNN ≤ 80 ms; integration test on device |
| Embedding model verification | multilingual-e5-small (or fallback) matches reference cosine ≥ 0.99 |
| PDF text extraction route | Decide platform API vs PdfBox-Android with a 30-PDF corpus |
| Device capability detection | Profiles collected from 5 reference devices; verdict formula calibrated |

Deliverable: spike report + updated ADRs. **Go/no-go decision with you.**

## Milestone 1 — Foundation, Model Manager, Chat (7 weeks)

- Project skeleton, modules, design system in Compose (ported from prototype tokens).
- Native engine, scheduler, model registry, catalog, downloads (pause/resume/verify), GGUF import.
- Device profile, compatibility verdicts, onboarding.
- Chat: streaming, stop, regenerate, markdown, code blocks, copy, history, system prompts, context meter, summarisation.
- Settings basics, performance monitor v1.
- Exit: internal alpha on 5 reference devices; crash-free ≥ 99 %.

## Milestone 2 — Knowledge Studio + RAG (6 weeks)

- Parsers (TXT/MD/code/DOCX/PDF) in isolated process; chunking; embeddings; knowledge.db.
- KB dashboard, create KB, document details, re-index/remove, semantic search UI.
- Chat with KB selection, citations, attachments.
- RAG evaluation set in CI.
- Exit: recall@8 ≥ 0.85 on eval set; ingestion 20 pages/min on mid-range.

## Milestone 3 — Agents + Tools (6 weeks)

- Agent Studio (templates, create/edit, details), Tool Registry with 8 tools, permission gate, audit log.
- Single-agent loop with grammar-constrained tool calls.
- Agent memory: proposals, inbox, recall; memory screen.
- Exit: scripted scenario suite (40 scenarios) passes with FakeInferenceEngine; ≥ 80 % task success with real 4B model on curated tasks.

## Milestone 4 — Multi-agent orchestration (6 weeks)

- Supervisor, planner, scheduler (hybrid), reviewer, aggregator; budgets; retry/recovery; resume after process death.
- Workflow visualisation and run history.
- Foreground service policy for long runs (pending approval).
- Exit: 20 benchmark workflows complete within budgets on 8 GB device; cancellation ≤ 300 ms.

## Milestone 5 — Hardening and beta (5 weeks)

- Performance pass (baseline profiles, thermal tuning), accessibility audit, security review, fuzzing, migration tests.
- Closed beta (Play internal testing), feedback loop, docs.
- Exit: release candidate.

**Total to beta: ~33 weeks (≈ 8 months)** with the team above. Parallelisable: M2 parser work can start during M1 by the native engineer.

## Post-MVP backlog (V1.x)

OCR (Tesseract), folder sync, multimodal models, LiteRT-LM backend, OpenCL GPU by default on validated devices, user-defined fixed workflows, encrypted vault, export/import of agents, tablet layouts, reranker, model per agent with swap scheduling.

## Complexity estimate

| Area | Complexity | Main risk |
|---|---|---|
| Native runtime integration | High | llama.cpp churn, device variance, native crashes |
| Model manager + downloads | Medium | Resumable large downloads, storage edge cases |
| Chat | Medium | Streaming render performance, context management |
| Knowledge engine | High | PDF variance, multilingual embeddings, index consistency |
| Tools | Medium | Schema validation + grammar generation |
| Agents/orchestration | High | Small-model reliability, prompt design, budgets |
| Memory | Medium | Good extraction without noise; UX of approval |
| Device/perf | Medium-High | Thermal behaviour, OEM background restrictions |
| UI/design system | Medium | Polish and accessibility |
