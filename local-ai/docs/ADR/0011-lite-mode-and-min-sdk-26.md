# ADR-0011: Lite mode for 4 GB / 6 GB phones and minSdk 26

**Status:** Accepted (2026-11-17, requested with the design approval)

**Context.** The original blueprint targeted ≥ 8 GB phones with a limited 6 GB mode and minSdk 31. Support for 4 GB and 6 GB phones was requested. Many of those phones run Android 8–11 and use older CPU cores without dotprod/i8mm.

**Decision.**
1. Add device tiers `LITE_4` and `LITE_6` with their own runtime defaults (context, batch, threads, reply length, background unload) — see MODEL_RUNTIME_DESIGN.md §12.
2. Lower minSdk to **26 (Android 8.0)** and keep **arm64-v8a only**.
3. Build llama.cpp with all ARM CPU variants and dynamic backend loading so one APK runs fast on both old and new cores.
4. Add 0.6–1.7B "Lite" models to the curated catalog.

**Alternatives.** A separate "Lite" APK (more release overhead); keeping minSdk 31 (excludes many 4 GB phones); 32-bit ARM support (too little memory to be useful, large test cost).

**Consequences.** Wider device reach and a larger test matrix (add a 4 GB and a 6 GB reference device). APIs newer than 26 (SoC model, thermal headroom) are used only when available. Answer quality on 4 GB phones is limited by 1B-class models; the UI says so.
