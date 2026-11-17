# LOCAL AI — Product Requirements

Status: Draft for approval · Phase 1 output · Last updated 2026-11-17

## 1. Product summary

LOCAL AI is a private, offline-first Android app that turns a phone into a personal AI environment. It downloads and runs open-weight LLMs on the device, lets people chat with them, build specialised agents, run supervised multi-agent workflows, and ground answers in their own documents through local retrieval (RAG). Nothing leaves the device unless the user starts a model download or update.

### 1.1 Problem

People who want a capable assistant without sending their documents, notes and code to a server have poor options on mobile:

- Cloud assistants are convenient but require trusting a third party with private data.
- Existing on-device chat apps are single-model chat boxes: no knowledge bases, no agents, no tools, no memory the user controls.
- Desktop tools (Ollama, LM Studio, AnythingLLM, Open WebUI) solve parts of this but are not designed for phone RAM, battery, thermals or Android's background rules.

### 1.2 Target users

| Persona | Need | Primary modules |
|---|---|---|
| Privacy-conscious professional (lawyer, doctor, consultant) | Ask questions over confidential documents offline | Knowledge Studio, Chat |
| Developer / power user | Run and compare open models, tune parameters, code help on the go | Model Manager, Chat, Coding Agent |
| Student / learner | Study assistant over lecture notes and PDFs, works on flights and poor connectivity | Knowledge Studio, Learning Assistant |
| Tinkerer | Compose agents and workflows, experiment with tools | Agent Studio, Workflows, Tool Registry |

### 1.3 Product principles

1. **Local by default, honest about limits.** Every capability works offline once a model is installed. The UI states clearly what the device can and cannot run.
2. **The user owns state.** Memory, documents and conversations are visible, editable and deletable. Nothing is remembered silently.
3. **Safety through structure.** Agents act only through typed, registered tools with validated inputs. The app never executes model-generated code.
4. **Mobile physics first.** One loaded LLM at a time on most devices; workflows are scheduled around that constraint, not in denial of it.
5. **Progressive power.** Chat works in two taps; agents, workflows and tuning are there when wanted.

## 2. Scope classification

Legend: **MVP** = first public release · **V1.x** = planned follow-up · **Exp** = experimental, behind a flag · **No** = explicitly unsupported.

### 2.1 Model Manager

| Requirement | Class |
|---|---|
| Browse a curated catalog of supported GGUF models with size, quantisation, architecture, licence, recommended RAM | MVP |
| Download with progress, pause, resume (HTTP Range), cancel, SHA-256 verification | MVP |
| Import a `.gguf` file from local storage (Storage Access Framework) with header validation | MVP |
| Delete models and reclaim storage | MVP |
| Select active model; load/unload; show load state | MVP |
| Per-model inference profile: context length, temperature, top-p, top-k, min-p, repeat penalty, max tokens | MVP |
| Estimated runtime memory (weights + KV cache + overhead) that updates with context length | MVP |
| Device compatibility verdict per model (Runs well / Tight fit / Not recommended) | MVP |
| Model details screen (card, licence, chat template, benchmarks on this device) | MVP |
| On-device mini benchmark (prompt eval + generation tokens/s) | MVP |
| Browse arbitrary Hugging Face repos | V1.x (curated catalog only at MVP to control compatibility and licence display) |
| GPU offload on Adreno via OpenCL | Exp |
| NPU backends (QNN, MediaTek NeuroPilot) | V1.x / Exp via a second runtime |
| Vision / multimodal models | V1.x |
| Running two LLMs concurrently | Exp on ≥16 GB devices only |

### 2.2 AI Chat

| Requirement | Class |
|---|---|
| Streaming token output, stop generation, regenerate | MVP |
| Conversation list, rename, delete, search titles | MVP |
| Pick model per conversation (falls back to active model) | MVP |
| System prompt per conversation; presets | MVP |
| Markdown rendering incl. code blocks with copy and language label | MVP |
| Copy message | MVP |
| Attach a document to a conversation (ephemeral, chunked into a scratch index) | MVP |
| Select one or more knowledge bases; show cited sources under answers | MVP |
| Agent mode toggle (route the message to an agent or a workflow) | MVP |
| Context usage meter and automatic summarisation of older turns | MVP |
| Branching / edit-and-resend of earlier messages | V1.x |
| Voice input (on-device speech) | V1.x |
| Image input | V1.x (needs multimodal model) |

### 2.3 Agent Studio

| Requirement | Class |
|---|---|
| Create, edit, duplicate, delete agents | MVP |
| Fields: name, description, role, system prompt, assigned model (or "use active"), tools, knowledge bases, memory config, execution settings | MVP |
| Templates: Research, Coding, Personal Assistant, Document Analyst, Learning Assistant, Data Analyst | MVP |
| Execution settings: max steps, max tool calls, temperature override, timeout, tool confirmation policy | MVP |
| Agent details: run history, last status, memory, tools | MVP |
| Export / import agent definitions as JSON | V1.x |
| Agent marketplace / sharing | No (out of scope; conflicts with offline-first) |

### 2.4 Multi-Agent Orchestration

| Requirement | Class |
|---|---|
| Supervisor → planner → delegated agents → reviewer → aggregator pipeline | MVP |
| Plans are a DAG of steps with dependencies; execution is serialised on the single loaded model | MVP |
| Live visualisation: per-step state Pending / Running / Completed / Failed / Skipped / Cancelled | MVP |
| Cancellation of a whole run or a step | MVP |
| Retry with repair prompt (1 retry default), error recovery path | MVP |
| Budgets: tokens, steps, tool calls, wall clock, delegation depth | MVP |
| User-defined fixed workflows (no planner, explicit step list) | V1.x |
| True parallel LLM execution via multi-sequence batching | Exp |
| Different model per agent (model swapping between steps) | V1.x (swap cost made visible) |

### 2.5 Knowledge Studio

| Requirement | Class |
|---|---|
| Create multiple knowledge bases (shared or agent-specific) | MVP |
| Import PDF (text layer), DOCX, TXT, Markdown, source code files, notes | MVP |
| View documents, indexing status and errors; remove; re-index | MVP |
| Semantic search with hybrid scoring (vector + keyword) and source references | MVP |
| Document details: metadata, extracted text preview, chunk list | MVP |
| Configurable chunk size/overlap per KB | MVP (advanced section) |
| OCR for scanned PDFs and images | V1.x |
| Folder sync (watch a SAF tree for changes) | V1.x |
| Web page import | V1.x (needs network; opt-in) |

### 2.6 Tool Registry

| Requirement | Class |
|---|---|
| Built-in typed tools: Calculator, File Search, Document Reader, Note Manager, Knowledge Search, SQLite Query (read-only), Code Search, Agent Delegation | MVP |
| JSON-schema validated inputs; grammar-constrained tool call generation | MVP |
| Per-tool permission: Allowed / Ask every time / Off; per-agent enablement | MVP |
| Tool call audit log visible to the user | MVP |
| Third-party / plugin tools | No for MVP; V2 consideration with signed manifests |
| Shell, arbitrary code execution, network tools | **No** |

### 2.7 Memory

| Requirement | Class |
|---|---|
| Conversation memory with rolling summary | MVP |
| Agent persistent memory: facts, preferences, episodic summaries; view, edit, pin, delete, forget-all; per-agent on/off | MVP |
| Memory proposals require approval by default ("memory inbox") | MVP |
| Shared knowledge memory = knowledge bases (kept separate from agent memory) | MVP |
| Memory export | V1.x |

### 2.8 Platform

| Requirement | Class |
|---|---|
| Device capability detection + recommendations | MVP |
| Performance monitor (tokens/s, RAM, thermal status, battery) | MVP |
| Settings: theme, inference defaults, privacy, storage, data wipe | MVP |
| Works fully offline after model install | MVP |
| No account, no analytics, no ads | MVP |
| Opt-in crash reports (local file the user can share manually) | V1.x |
| Tablets / foldables layout | V1.x (phone-first at MVP, responsive) |
| Wear OS, Android TV, iOS | No |

## 3. Non-functional requirements

| Area | Target (MVP) |
|---|---|
| Supported devices | arm64-v8a, Android 8.0+ (API 26); 8 GB+ recommended; 4 GB and 6 GB phones run in Lite mode with 0.6–1.7B models (ADR-0011) |
| Time to first token (3–4B Q4, 512-token prompt, 8 Gen 2 class) | ≤ 3 s |
| Generation speed (same) | ≥ 8 tok/s CPU; UI never blocks |
| Cold model load (2.5 GB mmap) | ≤ 6 s on UFS 3.1 |
| App memory (excluding model) | ≤ 350 MB PSS |
| Retrieval latency (top-8 over 50k chunks) | ≤ 150 ms |
| Ingestion | ≥ 20 pages/min on a mid-range device, cancellable, resumable |
| Crash-free sessions | ≥ 99.5 % |
| Accessibility | TalkBack labels on all controls, 48 dp targets, dynamic type to 200 % |
| Privacy | Zero network calls outside Downloads/Updates; verifiable via a network log screen |

## 4. Key user journeys

1. **First run:** Welcome → privacy promise → device check → recommended starter model → download → first chat. Target: under 5 minutes on Wi-Fi.
2. **Ask my documents:** Knowledge → New KB → import 3 PDFs → indexing → Chat with KB selected → answer with tappable citations.
3. **Build an agent:** Agents → template "Document Analyst" → adjust prompt, attach KB, enable Document Reader → test run → save.
4. **Run a workflow:** Chat → Agent mode → "Supervisor" → request → live workflow view → final answer with per-agent contributions → inspect a failed step and retry.
5. **Review memory:** Agent → Memory → approve 2 proposed memories, delete 1, pin 1.

## 5. Success metrics (measured locally, shown to the user, never uploaded)

- Activation: first successful answer within the first session.
- Retention proxy: user-created KBs and agents.
- Quality proxy: regenerate rate, thumbs-down rate per model (local only).

## 6. Open questions for product approval

See section 12 of the final presentation (`local-ai/README.md`). The most important: minSdk, curated-only catalog at MVP, background execution policy, and default embedding model language coverage.
