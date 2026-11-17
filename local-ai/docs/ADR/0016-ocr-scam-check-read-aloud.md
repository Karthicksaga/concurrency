# ADR-0016: On-device text recognition, Scam check and "Read it to me"

**Status:** Accepted (2026-11-17, requested by the product owner)

**Context.** The owner asked for features that are genuinely useful and rarely found elsewhere. We chose two that only work well offline and privately, in Indian languages, on a 6 GB phone:
- **Scam check:** is this SMS, WhatsApp message or screenshot a fraud?
- **Read it to me:** point the camera at a letter, bill, form or medicine label; hear it read aloud and explained simply.

Both need text recognition (OCR). OCR also fixes two old gaps: scanned PDFs in knowledge bases, and "photo → Word/PDF".

## 1. OCR engine

| Option | Offline | Play services | Indian scripts | Verdict |
|---|---|---|---|---|
| **Tesseract 5 (Tesseract4Android 4.9.0, Apache-2.0)** | Yes | Not needed | Hindi, Tamil, Telugu, Kannada, Malayalam, Bengali, Marathi | **Chosen** |
| ML Kit Text Recognition | Bundled models yes | Library depends on GMS; SDK may send usage data to Google | Latin + Devanagari (+ CJK) only | Rejected: privacy promise, no Tamil |
| The vision model itself | Yes | — | Weak on small text | Kept as a complement, not the OCR |

- Native libraries are arm64 only, **16 KB page aligned** (checked: `p_align 0x4000`) and add ≈ 7.5 MB. The JitPack repository is restricted to this one Maven group.
- Language packs come from `tessdata_fast`, pinned to commit `87416418…` and **SHA-256 verified**:
  - English (4.1 MB) ships in the APK.
  - Hindi, Tamil, Telugu, Kannada, Malayalam, Bengali and Marathi (0.9–5.3 MB each) download on demand from `raw.githubusercontent.com`. That host is new, and is used only for these files.
- Photos are now stored at ≤ 2048 px (was 1280) so small print stays readable. Scanned PDF pages are rendered at ~200 dpi, up to 20 pages in lite mode and 60 otherwise.
- **Verified on host (2026-11-17)** with Tesseract 5.3.4 and the pinned packs on rendered screenshots:
  - English scam SMS: read exactly, except a link split at a hyphen across two lines.
  - Tamil electricity-cut message: every word correct.
  - Cleanup added for both quirks: rejoin a link broken after a hyphen (keeping the hyphen), and drop zero-width joiners left at word ends.
- Not yet verified on a phone: Tesseract4Android speed and memory. The expected cost is a few seconds per photo on a mid-range phone.

**Where OCR is used**
- **Chat photos.**
  - Text is read first. Vision models get the image and the text.
  - **Text-only models can now answer about photos of documents.** The "can't see images" error appears only when a photo has no readable text.
- **Knowledge.** Scanned PDFs and photos (JPG/PNG/WebP) are indexed.
- **Document Studio.** Scanned PDFs and photos of pages convert to Word, PDF or Markdown.
- **Scam check and Read it to me.**

## 2. Scam check

- **Rules decide the verdict, and the model only explains it.**
  - The rules are deterministic and run without any model. Their decisions are explainable and unit-tested.
  - Small models are unreliable judges and can be talked out of a verdict by the message itself.
- About 13 rule groups with weights cover the common Indian frauds:
  - OTP/PIN requests; "enter UPI PIN to receive"; remote-access apps and APKs; "digital arrest", CBI, customs and parcel threats;
  - KYC or account blocking; electricity disconnection; prizes and lotteries; task/like jobs; guaranteed returns; fees to receive money;
  - urgency; personal numbers to call; unexpected refunds.
  - Links: shorteners, bare IPs, risky top-level domains, plain http, and **lookalike brand domains** (a brand name on a domain that isn't the brand's official one).
  - Some patterns are written in Hindi and Tamil. Genuine "do not share your OTP" bank messages are recognised as safe.
- Verdict levels:
  - **Likely a scam:** any strong signal (weight ≥ 6), or a total score ≥ 8.
  - **Suspicious:** a total score ≥ 3.
  - **No common scam signs:** otherwise, always shown with "still be careful".
- The model writes 3–5 simple sentences in the chosen language. The message is wrapped as data, and the model is told never to suggest opening links, calling numbers, paying or installing anything.
- Fixed advice is shown with every result: never share an OTP or PIN; check via official numbers; call **1930** or use cybercrime.gov.in; report on Sanchar Saathi (Chakshu).
- **Input:** paste, type, a screenshot or photo, or **Share → "Check with LOCAL AI"** from any app (`ACTION_SEND` for text and images). The app still **never reads SMS** (ADR-0013 update): it only sees what the user shares.
- Tests: 4 JVM tests (real-style scams in English, Tamil and Hindi; genuine bank, OTP and order messages; link classes; OCR cleanup and packs). The prototype's JavaScript port gives the same verdicts on the same messages.

## 3. Read it to me

- Photo → OCR → the text is shown in large type and is editable to fix OCR errors.
- **Read as written:** text-to-speech, with the voice picked from the text's script (Tamil, Devanagari and so on).
- **Explain simply:**
  - The model says in the chosen language what the document is, lists dates, amounts, names and deadlines, and says what to do.
  - For a medicine label, it reads the name, dose and warnings exactly and adds "Ask a doctor or pharmacist before taking it."
  - The explanation is read aloud automatically.
- Works without a model (OCR + read aloud). The explanation needs a chat model.

**Consequences.**
- The APK grows from 16.3 MB to 21.5 MB: Tesseract and the English pack.
- One new download host, for language packs only.
- No new permissions. The share target needs none, and photos still come from the camera app or picker.
- The rules need updating as scams change. They are data-like and covered by tests, so new patterns are cheap to add.
