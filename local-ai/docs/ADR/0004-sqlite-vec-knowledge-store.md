# ADR-0004: Knowledge store — custom SQLite with sqlite-vec + FTS5

**Status:** Accepted (2026-11-17)

**Context.** We need vector KNN plus keyword search, transactional writes with chunk text, and small footprint. Android's framework SQLite cannot load extensions; the AndroidX bundled driver does not support extension loading.

**Decision.** Compile SQLite (with FTS5) and sqlite-vec statically into our native library and use it for `knowledge.db` only, behind `KnowledgeStore`/`VectorIndex` interfaces. Store int8-quantised vectors with a KB partition key. Room continues to own `app.db`.

**Alternatives.** sqliteai sqlite-vector (Android package, but Elastic License 2.0); ObjectBox (proprietary core); HNSW library (more RAM and complexity; unnecessary below ~200k chunks); pure Kotlin brute force (kept as fallback implementation).

**Consequences.** We own a small native SQLite build (security updates are our job). sqlite-vec is pre-1.0 with a small maintainer team: risk mitigated by the interface and the brute-force fallback. Two DB files need a reconciler for consistency.
