# Performance Strategy

Status: Draft for approval · Phase 3 output

## 1. Budgets

| Metric | 8 GB device (SD 8 Gen 2 class) | 12 GB | 16 GB |
|---|---|---|---|
| Default model | 3–4B Q4_K_M | 7–8B Q4_K_M | 8B Q5_K_M / 12B Q4 |
| Default context | 4,096 | 8,192 | 8,192 (16k opt-in) |
| KV cache type | q8_0 | q8_0 | f16 or q8_0 |
| Target generation | ≥ 8 tok/s | ≥ 7 tok/s (8B) | ≥ 6 tok/s (12B) |
| TTFT (512 prompt tokens) | ≤ 3 s | ≤ 4 s | ≤ 5 s |
| App PSS excl. model | ≤ 350 MB | ≤ 350 MB | ≤ 350 MB |
| Embedding model co-resident | Only while indexing/searching | Yes | Yes |

Numbers are targets to validate in Phase 0, not measurements.

## 2. Memory pressure plan

1. Never hold two generation models unless the device budget allows both (Exp, ≥16 GB).
2. Use mmap for weights; quantised KV cache.
3. Release the embedding model after 60 s idle on ≤ 8 GB devices.
4. React to `onTrimMemory` (see `MODEL_RUNTIME_DESIGN.md §7`).
5. Avoid JVM copies of large data: embeddings via direct `ByteBuffer`; chunk text streamed from SQLite cursors.
6. Image-free UI; no WebView.

## 3. Latency strategies

| Technique | Benefit |
|---|---|
| Prefix KV caching per agent | Saves 1–4 s per agent switch in workflows |
| Conversation KV reuse (append-only turns) | Avoids re-evaluating history each turn |
| Rolling summaries | Keeps prompts small → faster prompt eval |
| Retrieval top-k tuned to 6–8 | Prompt eval dominates TTFT on mobile |
| Grammar only when needed | Grammar sampling costs ~5–15 % speed |
| Q4_0 + ARM repack for CPU, Q4_K_M default | Choose per device after benchmark |
| Warm model on app foreground if recently used | Hides load time |

## 4. UI performance

- Token coalescing to ~20 updates/s; markdown parsed incrementally off main thread.
- Baseline Profiles for chat, models, agents screens; Macrobenchmark startup ≤ 600 ms cold (excluding model load).
- Workflow graph drawn with Compose `Canvas`, ≤ 20 nodes, no per-frame allocations.

## 5. Battery and thermal

- Thread count follows thermal headroom (`getThermalHeadroom` forecast) to avoid hitting throttling cliffs; a slightly slower steady state beats a fast start then a 50 % drop.
- Background indexing runs with WorkManager constraints: `requiresBatteryNotLow`, optionally `requiresCharging` (user setting, default on for >200 documents).
- Performance monitor shows tok/s, temperature status, battery drain estimate (mAh per 1k tokens, measured via `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` where reliable).

## 6. Storage

- Before download: require `free ≥ model_size × 1.1 + 1 GB`.
- Storage screen lists models, documents, indexes, caches with sizes and one-tap cleanup for caches.
- KV caches capped at 200 MB (LRU); document copies deduplicated by hash.

## 7. Measurement

- Internal benchmark harness (`:benchmark` module) runs fixed prompts on fixed models: TTFT, prompt tok/s, gen tok/s, peak PSS, temperature delta over a 10-minute soak.
- Reference device lab (minimum): one 8 GB Snapdragon, one 12 GB Snapdragon, one 12 GB MediaTek Dimensity, one 16 GB flagship, one 8 GB Tensor. Results tracked per release; regressions > 10 % block release.
