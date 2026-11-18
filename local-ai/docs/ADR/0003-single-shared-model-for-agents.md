# ADR-0003: Logical agents share one loaded model

**Status:** Accepted (2026-11-17)

**Context.** A 4B Q4 model needs ~3–3.5 GB at 4k context. Two such models exceed the safe budget on 8 GB devices, and parallel decoding on phone CPUs gives little speedup because one decode already saturates performance cores.

**Decision.** Agents are configurations. One generation model is loaded; the inference scheduler serialises LLM steps and swaps prompts and cached KV prefixes between agents. Agents keep an optional `model_id` so per-agent models can be introduced later with explicit swap scheduling.

**Alternatives.** One model per agent (RAM-prohibitive); multiple contexts on one model (KV memory per context; Exp later); LoRA adapters per agent (interesting V2: llama.cpp supports hot-swapped LoRA).

**Consequences.** Predictable memory. Workflows are slower than server equivalents; the UI must show progress per step and budgets. Prefix caching becomes important.
