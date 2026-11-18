# ADR-0001: Native Android with Kotlin, Compose and Material 3

**Status:** Accepted (2026-11-17)

**Context.** The app needs tight control over native memory, threads, foreground services, thermal APIs and JNI. Cross-platform frameworks (Flutter, React Native, KMP with Compose Multiplatform) add a bridge between UI and the native runtime and lag on new Android APIs.

**Decision.** Kotlin, Jetpack Compose, Material 3, MVVM with unidirectional `StateFlow` state, Hilt, Coroutines/Flow. Domain and engine modules are plain Kotlin so they can later become Kotlin Multiplatform if an iOS version is ever wanted.

**Alternatives.** Flutter (good UI velocity, but FFI to llama.cpp plus platform channels for Android services); React Native (same, plus JS memory overhead); Compose Multiplatform (viable later; not needed now).

**Consequences.** Android-only at first. Best access to platform APIs and performance tooling. Engines stay portable.
