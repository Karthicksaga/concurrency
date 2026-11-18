# ADR-0015: Voice, photos (vision) and Document Studio on the phone

**Status:** Accepted (2026-11-17, requested by the product owner)

**Context.** The owner asked for three things: a real voice conversation like the Claude app, the ability to take or upload a photo and have it explained, and PDF/Word generation plus Word → PDF conversion "with the help of AI". Everything must work offline, including on 4–6 GB phones in lite mode.

## 1. Photos: llama.cpp `mtmd` with a separate image encoder

| Option | Verdict |
|---|---|
| **llama.cpp `mtmd` (libmtmd) in the same engine** | **Chosen.** Same runtime, same thread and memory budget; vision models ship as GGUF + `mmproj` |
| A separate vision runtime (ONNX/TFLite captioner) | Rejected: second runtime, caption only, cannot answer questions |
| OCR only (ML Kit) | Rejected: needs Play Services; does not "explain" a photo. Still planned for scanned PDFs |

- Catalog vision models download **two checksum-verified files** (model + image encoder): SmolVLM 500M (≈ 0.55 GB, fits 4 GB phones), SmolVLM2 2.2B, Qwen2.5-VL 3B, and Gemma 3 4B (its encoder is now part of the download; already-installed copies show **Add image support**).
- The encoder loads right after the model. If it fails, the text model still works and a photo gets a clear "this model can't see images" message.
- Photos are stored app-private (≤ 1280 px JPEG, EXIF rotation applied) and resized to **512 px in lite mode, 768 px otherwise** before encoding. This means fewer image tokens, less memory and a faster first word.
- The latest photo is re-sent while it is within the last two user messages, so follow-up questions still see it. Older photos are replaced by `[photo]` plus the model's earlier description.
- **No CAMERA permission.** The camera app takes the photo through `ACTION_IMAGE_CAPTURE` and writes it to a FileProvider URI we own. The gallery uses the system photo picker.
- Verified on host (2026-11-17): SmolVLM 500M Q8_0 with its encoder described a newspaper photo correctly (152 prompt tokens including the image); a text turn after an image turn works; malformed image buffers are rejected.
- Not yet verified: M-RoPE models (Qwen2.5-VL) on the phone. SmolVLM is the recommended choice for lite mode.

**Update (2026-11-18): automatic model choice for photos.** The owner has a chat model and a vision model installed, and expected the right one to answer a photo. Each photo is now read with OCR before any model loads:
- **Readable text** (bill, letter, screenshot): the active chat model answers from the text. It reasons better than a small vision model.
- **No readable text** (a person, a plant, a place), and the active model can't see: the best-fitting installed vision model is loaded for that message only (`activate(persist = false)`), and the chat shows "Switching to … to look at the photo".
- The active model comes back on the next text message. "Can't see images" appears only when no vision model is installed.

## 2. Voice: Android SpeechRecognizer (on-device first) + TextToSpeech

| Option | Size | Verdict |
|---|---|---|
| **Android `SpeechRecognizer`, on-device recognizer preferred** | 0 MB in the APK | **Chosen now** |
| whisper.cpp in the app | +75–500 MB model, second copy of ggml | Later, behind the same interface. Linking a second ggml next to llama.cpp's risks symbol clashes; would need its own `.so` namespace or sherpa-onnx |
| Cloud speech APIs | — | Rejected (offline promise) |

- Android 12+ with an on-device recognizer uses `createOnDeviceSpeechRecognizer`. Otherwise we use the system recognizer with `EXTRA_PREFER_OFFLINE`, which stays offline when the language pack is installed. The voice screen says which one is in use.
- Replies are spoken by the phone's TTS engine **sentence by sentence while the model is still writing**. `SentenceStream` handles abbreviations and decimals, holds back code blocks, and strips markdown.
- The voice conversation loop is listen → answer → speak → listen. Tapping the orb interrupts at any point, and hands-free mode can be turned off. The same conversation appears in Chat.
- Dictation (mic in the composer), a read-aloud button on every answer and an optional **Read replies aloud** setting. Choose the voice language (incl. Indian languages) and speed in Settings › Voice.
- Permission: `RECORD_AUDIO`, requested the first time the mic is used.

## 3. Document Studio: our own writers, no new libraries

- **One format-neutral block model** (headings, paragraphs, bulleted and numbered lists, tables, code, quotes, rules, bold/italic/monospace). Markdown, Word and PDF all convert through it.
- **Word (.docx)**: our own OOXML writer (styles, real numbered and bulleted lists, tables with header rows, code and quote styles) and reader (headings by style or outline level, lists via `numbering.xml`, tables, run formatting). It is pure Kotlin and unit-tested, including a write → read round trip and an XML well-formedness check of every part.
- **PDF**: Android `PdfDocument` + `StaticLayout`. Text stays real (selectable and searchable), with automatic page breaks, headings kept with what follows, tables, code blocks and page numbers. All scripts the phone can display render through font fallback. PDF input uses PdfBox text extraction (scanned PDFs still need OCR).
- **AI**:
  - **Write** a report, letter, email, minutes, resume, essay or proposal from a description, with a streamed preview and an editable draft.
  - **Convert with AI on the way**: fix grammar, summarize, translate, make formal or simplify. Long documents are processed part by part. Each part is ≈ 30 % of the context window (≈ a page in lite mode), so it fits 2K contexts. Summaries use map → reduce.
- Exports stay in app storage (`files/exports`) until the user opens, shares (FileProvider, one-file temporary grant) or saves them to the phone (Storage Access Framework). Any chat answer can be saved as PDF or Word.
- Rejected: Apache POI (≈ 10 MB, slow on Android), iText (AGPL), and server-side conversion (privacy).

**Consequences.**
- APK grows only by our code; models are optional downloads.
- Voice quality depends on the phone's speech and TTS packs; we point users to the settings.
- Word → PDF keeps structure and formatting but not exact layout (fonts, images, columns). Images inside .docx are not carried over yet.
