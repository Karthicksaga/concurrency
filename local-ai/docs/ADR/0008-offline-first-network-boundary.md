# ADR-0008: Offline-first with a single network boundary

**Status:** Accepted (2026-11-17)

**Context.** The privacy promise must be verifiable, not just stated.

**Decision.** Only `:data:downloads` may use the network (enforced by module boundaries, lint, and a CI guard). No analytics or crash SDKs. A local network log lists every request. Model catalog is bundled and optionally refreshed.

**Alternatives.** Optional cloud fallback for large models (rejected for MVP; contradicts positioning); opt-in analytics (deferred; would require separate consent and module).

**Consequences.** No remote insight into crashes or quality; we rely on beta testers sharing local reports voluntarily and on a strong device lab.
