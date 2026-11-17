# LOCAL AI — Android app

Native Kotlin + Jetpack Compose app that runs GGUF language models **on the phone** with llama.cpp: model manager, chat, Agent Studio, tools, agent memory, knowledge bases with local search (RAG) and supervisor multi-agent workflows, plus **Lite mode** for 4 GB and 6 GB phones. Implementation notes: `../docs/ADR/0012-milestone-2-4-implementation-choices.md`.

## Install on your phone

1. On the phone, open the repository's **Releases** page and pick **`local-ai-latest`** (a pre-release built by GitHub Actions on every push that touches `local-ai/android/`).
2. Download `LocalAI-0.1.N.apk`, open it, and allow "Install unknown apps" for your browser when Android asks.
3. Open **LOCAL AI**. The onboarding checks your phone and recommends a starter model; downloads use Wi-Fi by default.

Requirements: 64-bit ARM phone (arm64-v8a), Android 8.0 or newer, about 1–3 GB free storage per model.

The APK is signed with a **public test key** committed in `app/debug-release.keystore` so new builds install over old ones. It is not a Play Store release key.

## What works in this build

| Area | Status |
|---|---|
| On-device inference (llama.cpp b12100, CPU, all ARM variants selected at runtime) | ✅ |
| Device check, tiers (4 / 6 / 8 / 12 / 16 GB) and **Lite mode** (auto / on / off) | ✅ |
| Curated catalog of 11 GGUF models (0.6B–8B) with memory estimate and "Runs well / Tight fit / Not recommended" verdict | ✅ |
| Resumable downloads (pause / resume / cancel), SHA-256 verification, Wi-Fi-only option, progress notification | ✅ |
| Import your own `.gguf` (header validated, metadata read for the memory estimate) | ✅ |
| Per-model context length, temperature, top-p | ✅ |
| Chat: streaming, stop, regenerate, copy, history, system prompt, markdown + code blocks, tok/s | ✅ |
| KV-cache reuse between turns, automatic trimming of old turns when the context is full | ✅ |
| Hidden reasoning for "thinking" models (toggle in Settings) | ✅ |
| Performance screen: tok/s, free RAM, app memory, thermal status, battery, CPU features | ✅ |
| **Agent Studio:** 6 templates, create/edit/duplicate/delete, per-agent model, tools, knowledge bases, memory and limits; agent details | ✅ |
| **Tools:** Calculator, Knowledge Search, Document Reader, Code Search, Note Manager, SQLite Query (read-only), File Search (granted folder); Allow/Ask/Off permissions, permission dialog, audit log | ✅ |
| **Agent memory:** approval inbox, "Suggest memories" from a chat, pin/edit/delete/forget all, per-agent on/off and auto-save | ✅ |
| **Knowledge:** knowledge bases (shared or one agent), import PDF/DOCX/TXT/Markdown/code and notes, on-device chunking + embeddings, hybrid search, document details, re-index, notes | ✅ |
| **Chat with sources:** pick knowledge bases and an agent per chat; answers cite `[n]` with tappable sources; tool calls shown inline | ✅ |
| **Supervisor workflow:** planner → agent tasks (dependency order, one shared model) → review → aggregate; live graph, retry failed step, cancel, history; also from chat via Agent mode › Supervisor | ✅ |
| **Folder sync:** link a phone folder to a knowledge base; new, changed and deleted files sync every 6 h, on demand and before the digest | ✅ |
| **Phone tools:** Phone Dialer (opens the dialer, you press Call; contacts by name optional), SMS Reader (read-only, off by default) | ✅ |
| **Daily digest:** at your chosen time, an on-device summary of SMS, documents, notes and workflows → notification + saved chat + Email button (opens your email app pre-filled) | ✅ |
| **Automatic digest email (opt-in):** sends through your own mail server (Gmail/Outlook/Yahoo/Zoho/custom) with an app password stored in the Android Keystore; test email; falls back to the Email button | ✅ |
| OCR for scanned PDFs, isolated parser process, GPU/NPU, Gmail sign-in (OAuth) | ⏳ Later |

## Lite mode

Turned on automatically for phones that report less than ~6.6 GiB of RAM. It uses smaller models, a 2K context, smaller processing batches, shorter replies and releases the model soon after you leave the app. Details: `../docs/MODEL_RUNTIME_DESIGN.md §12` and `../docs/ADR/0011-lite-mode-and-min-sdk-26.md`.

| Phone | Recommended models |
|---|---|
| 4 GB | Llama 3.2 1B, Qwen3 0.6B, Gemma 3 1B |
| 6 GB | Qwen3 1.7B, SmolLM2 1.7B (Llama 3.2 3B is a tight fit) |
| 8 GB | Llama 3.2 3B (4B models are a tight fit) |
| 12 GB+ | Qwen3 4B, Phi-4-mini, Gemma 3 4B; 7–8B as a tight fit |

## Build it yourself

```bash
git submodule update --init --depth 1 local-ai/android/app/src/main/cpp/llama.cpp
cd local-ai/android
# needs JDK 17+, Android SDK 35, NDK 28.2.13676358, CMake 3.31.6 (sdk.dir in local.properties or ANDROID_HOME)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The first native build compiles llama.cpp for every ARM CPU variant and takes 15–40 minutes.

## Tests

```bash
./gradlew testReleaseUnitTest          # JVM (13 tests): tiers, lite policy, verdicts, GGUF reader, calculator, chunker, sources, templates
native-test/run.sh [model.gguf] [embedder.gguf ...]   # host build of la_jni.cpp + llama.cpp driven through JNI with real models
```

`native-test` (last run 2026-11-17 with Qwen3 0.6B Q8_0, lite settings: 2K context, batch 128, q8_0 KV): 13/13 passed —
correct answers, KV-cache reuse on the second turn (31 tokens), stop/abort mid-generation and recovery, context-overflow error,
control tokens in user text treated as plain text, intact multi-byte UTF-8, NO_MODEL after unload.

Also verified on host (2026-11-17): grammar-constrained JSON (tool decision picks the calculator for arithmetic; planner emits a valid task list),
and both embedding models (relevant passage ranks first; multilingual e5 matches a German query to an English passage).

Not yet tested on a physical phone: the UI and on-device speed. Please report what you see (model, phone, tok/s on the Performance screen).

## Layout

```
app/src/main/
├─ cpp/                    CMakeLists.txt, la_jni.cpp (our C/JNI layer), llama.cpp (submodule, pinned b12100)
└─ java/com/localai/app/
   ├─ runtime/inference/   LlamaNative (JNI), LlamaEngine (single-slot engine, streaming Flow, abort)
   ├─ runtime/device/      DeviceCapabilityService, DeviceTier + lite-mode defaults
   ├─ data/catalog/        curated models, MemoryModel (estimate + verdict)
   ├─ data/models/         ModelRepository (activate under lite policy, GGUF import + reader)
   ├─ data/downloads/      DownloadManager + DownloadWorker (resume, SHA-256)
   ├─ data/chat/           ChatService (app-wide generation, context trimming)
   ├─ data/db/             Room: installed models, conversations, messages
   ├─ data/settings/       DataStore settings, RuntimePolicy (what lite mode changes)
   ├─ core/designsystem/   tokens and components ported from the prototype
   └─ feature/             onboarding, home, models, chat, settings, monitor
```

Deviation from the blueprint: modules are packages inside one Gradle module for now; they will be split into the planned Gradle modules when agents and knowledge arrive (Milestones 2–3).
