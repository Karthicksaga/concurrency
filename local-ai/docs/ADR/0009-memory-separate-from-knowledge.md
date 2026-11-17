# ADR-0009: Agent memory is separate from knowledge, approval by default

**Status:** Accepted (2026-11-17)

**Context.** Mixing learned "memories" with user documents confuses provenance and makes deletion unreliable. Small models also produce noisy memories.

**Decision.** Three memory types with separate storage: conversation (messages + rolling summary), agent memory (`agent_memory` + `memory_vec`, per agent), shared knowledge (KBs). Agent memories are proposals until approved (per-agent auto-save option). Delete is hard delete including vectors.

**Alternatives.** Store memories as notes in a KB (simple, but provenance blurs); fully automatic memory (noisy, surprising).

**Consequences.** Extra UI (Memory inbox) and tables, but clear user control and explainability.
