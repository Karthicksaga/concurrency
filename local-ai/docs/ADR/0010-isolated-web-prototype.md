# ADR-0010: Dependency-free web prototype, isolated from the Android codebase

**Status:** Proposed

**Context.** We need a high-fidelity, clickable prototype before committing to the Android build, viewable on any phone or laptop without tooling.

**Decision.** Build the prototype as plain HTML/CSS/JavaScript in `local-ai/prototype/` with no build step and no dependencies. All data is mock data labelled as sample. Design tokens are defined as CSS variables named to map 1:1 onto the future Compose theme.

**Alternatives.** Figma (not interactive enough for workflow simulation); Compose prototype (slower to iterate, needs device); React/Vite (adds tooling for little gain).

**Consequences.** The prototype is throwaway code; only its tokens, component inventory and interaction specs carry forward into `UI_UX_SPECIFICATION.md`.
