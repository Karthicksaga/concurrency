# ADR-0006: Custom Kotlin supervisor orchestrator with grammar-constrained JSON

**Status:** Accepted (2026-11-17)

**Context.** Server agent frameworks are Python/JS-first and assume hosted models with large contexts and cheap parallel calls. On device we have one small model, limited context, and strict resource budgets.

**Decision.** A small Kotlin orchestrator: supervisor routes; planner emits a plan DAG (≤ 6 tasks) under a GBNF grammar; a hybrid scheduler executes LLM steps serially and tool calls concurrently; reviewer (1 cycle) and aggregator finish. Budgets on steps, tool calls, tokens, time and delegation depth. Runs persist per step for resume.

**Alternatives.** Embedding LangGraph-like semantics via an interpreter (heavy, unsafe); fully sequential fixed pipeline (simpler but cannot adapt to request); free-form ReAct without grammar (unreliable with 3–8B models).

**Consequences.** Full control over budgets, cancellation and visualisation. We own prompt engineering for small models, which needs an evaluation suite.
