# ADR-0018: Automatic tools in normal chat

**Status:** Accepted (2026-11-17, requested by the product owner)

**Context.** Tools ran only when the user picked an agent that had them. The owner asked for the AI to choose skills and tools by itself during a conversation. On a phone, an extra model "decision" step before every reply would add seconds to each answer.

**Decision.** A two-stage router, used in normal chat (no agent selected) when **Settings › Inference › Use tools automatically** is on (default):

1. **`SkillRouter`, instant and on the phone** (pure Kotlin, unit-tested) proposes candidate skills from the message:

   | Skill | Triggered by | Runs directly ("sure")? |
   |---|---|---|
   | Calculator | arithmetic, "18% of ₹2,500" | Yes, when the message is mostly a sum or asks "what is / calculate" |
   | Date & Time | "what's the date / day / time" | Yes. Local models don't know today's date. |
   | Scam check | a pasted message (≥ 40 characters) where the scam rules see signs, or "is this genuine/real/a scam" | Yes |
   | Create document | "save / make / write … as PDF / Word" | Yes |
   | Phone dialer | "call +91 …" | Yes, but its Ask permission still shows a dialog |
   | Knowledge search | questions when knowledge bases exist (all of them, unless some are selected) | Only for "in my documents / lease / policy …" |
   | Note manager | "remember that…", "my notes" | No: the model picks the action |

   Ordinary messages produce no candidates, so they cost nothing extra. Dates and phone numbers are not treated as sums.
2. **One grammar-constrained decision**, only for unsure candidates. The model chooses `answer` or one of those tools (`json_schema_to_grammar`, temperature 0, ≤ 100 tokens), so it can't call a tool that wasn't proposed.

- Tool results go into the system prompt with "use them, don't recompute". Every call is shown under the answer, and the Allow / Ask / Off permissions and the audit log apply as for agents.
- "Create document" makes the model write the full document in Markdown. The answer is then saved with Document Studio's PDF/Word writers, and the file appears under the answer with **Open** and **Share**.
- Agents keep their own tool lists and their decision loop. Photo messages skip automatic tools; OCR and vision handle photos.

**Consequences.**
- Fast on phones: deterministic routing for the common cases, at most one short model decision when a skill is plausible but unclear.
- The router is keyword-based (English, with a few Hindi and Tamil date phrases), so unusual phrasings may not trigger a tool. The model then answers on its own, as before.
- Tests: 5 router tests (no tools for ordinary chat; calculator extraction; date, document, dialer and notes; pasted scam; knowledge only when it exists). The prototype mirrors the date, scam and PDF cases.
