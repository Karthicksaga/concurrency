# ADR-0007: Typed tools only; no model-generated code execution

**Status:** Proposed

**Context.** Agents can be steered by prompt injection in documents. Executing generated code or shell commands on a personal device is an unacceptable risk.

**Decision.** Tools are compiled into the app, each with a JSON Schema, risk class, timeout and permission (Allowed / Ask / Off). Arguments are generated under grammar and validated again before execution. No interpreter, shell, network or dynamic code loading. SQLite Query is read-only through an authorizer on an allowlisted user-data DB.

**Alternatives.** Sandboxed JS/Python interpreter (attack surface, size); WASM sandbox (V2 research only).

**Consequences.** Lower flexibility (no "run this script"). Strong, explainable safety story. New tools ship with app updates.
