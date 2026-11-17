# ADR-0017: Natural on-device voices and talking over the assistant (barge-in)

**Status:** Accepted (2026-11-17, requested by the product owner)

**Context.** The owner asked for a voice mode like ChatGPT's or Claude's: just start talking to interrupt (no button), and a natural human voice instead of the phone's robotic text-to-speech.

## 1. Barge-in (interrupt by talking)

- While the assistant thinks or speaks, `BargeInMonitor` reads the microphone through Android's voice-call input (`VOICE_COMMUNICATION`), with `AcousticEchoCanceler` and `NoiseSuppressor` where the phone has them.
- `BargeInLogic` (pure Kotlin, unit-tested) decides when the user has started talking:
  - The first 450 ms of each turn calibrate the background. That background includes the assistant's own voice leaking into the mic after echo cancellation.
  - Speech then counts only when it stays above that background by **11 dB for 280 ms** (Normal) or **7 dB for 200 ms** (Sensitive).
  - Short gaps between syllables don't reset the timer; coughs and taps don't trigger it.
- On barge-in: speech and generation stop, the partial answer is kept, the mic is handed to the speech recognizer and the conversation continues.
- **Limitation:** Android can't hand one mic stream to the recognizer, so the first ~0.3–0.5 s of the interruption can be lost. A future option is `EXTRA_AUDIO_SOURCE` (Android 13+), which would feed buffered audio to the recognizer.
- Off / Normal / Sensitive, in the voice screen and Settings › Voice. Earphones work best.
- Tests (5): the assistant's echo alone never triggers it; the user talking over it triggers within ~0.3 s; coughs and taps don't; syllable gaps don't reset; Off never triggers.

## 2. Natural voices

| Option | Quality | Speed (real-time factor, 2-thread desktop) | Verdict |
|---|---|---|---|
| Android TTS (default engine) | Robotic on many phones | fast | Kept as fallback and for other languages (e.g. Tamil) |
| **Piper medium (VITS) via sherpa-onnx** | Natural | **0.05–0.06** | **Default choice:** Amy (US), Jenny (UK), Priyamvada and Rohan (Hindi) |
| Piper high (Lessac HD) | More natural | 0.34 | Offered, marked "slower" |
| Kitten nano (8 expressive English voices) | Expressive | 0.28 | Offered, marked "slower" |
| Kokoro 82M int8 (best quality) | Very natural | **1.29**: slower than real time even on a desktop | Rejected for live voice on phones |

Measured on the host with sherpa-onnx 1.13.8 and the exact pinned models (2026-11-17). A phone is roughly 2–4× slower, and it shares the CPU with the language model.

- **Runtime:** sherpa-onnx 1.13.8 AAR (Apache-2.0, ONNX Runtime).
  - Its native libraries are arm64 and **16 KB aligned** (checked).
  - The AAR is not on Maven Central, so the build fetches the pinned release and checks its **SHA-256** before use. The file is not committed.
  - It adds ≈ 13 MB to the APK.
- **Pronunciation data:** `espeak-ng-data`, trimmed to English and the Indian languages (19 MB → 2.7 MB), ships in the APK.
- **Voice files:** downloaded on request from Hugging Face (`csukuangfj/*`), pinned to commits and SHA-256 verified (24–114 MB each).
- **Playback:**
  - Synthesis runs one sentence ahead of playback, on its own thread.
  - Audio goes through `AudioTrack` (assistant usage).
  - Sentences in a script the voice can't read (e.g. Tamil with an English voice) are spoken by the phone's voice, in order.
  - The voice is released when the app goes to the background, freeing ≈ 100–200 MB.
- Settings › Voice: the phone's voice or a natural voice, each with Download, Preview and Remove; Kitten offers Voice 1–8, picked by ear.

## 3. Release-build fix found while adding the native library

R8 renamed fields that native code reads by name. Examples: Tesseract's `TessBaseAPI.mNativeData` → `a`, and Leptonica's `Pix.mNativePix` → `a`. The first text recognition therefore aborted in native code. This explains the 0.1.9 crash when indexing a folder of photos; 0.1.10's isolated OCR process contained it but didn't fix it. Keep rules were added for Tesseract/Leptonica and sherpa-onnx (whose AAR ships an empty rules file). The mapping file now shows the fields unrenamed.

**Consequences.**
- The APK grows from 21.6 MB to 34 MB.
- Natural voices cost 60–115 MB of storage each, only when downloaded.
- Not yet verified on a phone: actual synthesis speed while the model is generating, and how well echo cancellation works on the speaker.
