# ADR-0012: Implementation choices for agents, knowledge, tools, memory and workflows

**Status:** Accepted (2026-11-17)

**Context.** Agents, Knowledge Studio, the tool registry, memory and supervisor workflows were built into the Android app in one step. A few blueprint details were simplified to ship a working, testable version on phones, including 4 GB / 6 GB lite mode.

**Decisions.**
1. **Vector search:** vectors are stored as float32 BLOBs in Room (`chunk.embedding`) and scanned in memory per knowledge base. This is the brute-force fallback named in ADR-0004. Keyword search uses Room's **FTS4** table. Results are fused with Reciprocal Rank Fusion. The custom SQLite + sqlite-vec build is postponed until corpora exceed ~50k chunks.
2. **Embedding models:** `multilingual-e5-small` (TwinSunsLLC Q8_0 conversion) and `bge-small-en-v1.5` (ggml-org Q8_0), pinned by SHA-256. Three other public e5 conversions were rejected: one fails to load in llama.cpp (outdated BERT metadata), one ranks badly, and one crashes during embedding. Verified with `android/native-test`.
3. **Without an embedding model**, documents are still indexed for keyword search. The UI labels this "Keyword only".
4. **Tool calls:** each decision is a grammar-constrained JSON object `{action, tool, input}` with a single string input, and each tool documents and validates its own input format. This is more reliable with 1–4B models than nested per-tool schemas. Tools set to "Ask" show a permission dialog in chat. Inside background workflows, "Ask" tools are declined automatically and logged.
5. **SQLite Query** runs on a separate read-only connection, allows only `SELECT` on the `note` and `document` tables, and rejects any statement containing write keywords or `;`.
6. **Workflows:** the planner emits at most 4 tasks (3 in lite mode) with `after` dependencies that may only point to earlier tasks, so the plan is acyclic by construction. Tasks run in order on the single model slot. A failed task is retried once; tasks that depend on it are skipped. The reviewer runs only on non-lite devices and the aggregator always runs. Lite mode disables tools inside workflows and shortens outputs.
7. **Memory:** recall uses keyword overlap + importance + recency + pinning (no vectors yet). Proposals are generated on request ("Suggest memories") with a grammar-constrained extractor and land in the approval inbox unless auto-save is on.
8. **Document parsing** (PdfBox-Android, a streaming DOCX reader, text, Markdown and code) runs in the app process with size and page limits. Moving it into an `isolatedProcess` service is a hardening item.

**Consequences.** Everything runs and is testable on phones today. The known gaps (isolated parser process, vector memory recall, sqlite-vec at scale, OCR) are tracked in the roadmap.
