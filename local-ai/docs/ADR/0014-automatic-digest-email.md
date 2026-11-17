# ADR-0014: Opt-in automatic digest email through the user's own SMTP server

**Status:** Accepted (2026-11-17, requested by the product owner)

**Context.** The daily digest (ADR-0013) opened the user's email app pre-filled, and the user pressed Send. The owner asked for fully automatic sending. Sending email automatically needs credentials and network access beyond model downloads (ADR-0008).

**Options considered.**

| Option | Setup | Requires | Verdict |
|---|---|---|---|
| A. SMTP with an app password | The user creates an app password (Gmail, Outlook, Yahoo, Zoho, others) | Nothing from us | **Chosen now** |
| B. Gmail API via Google sign-in (OAuth, `gmail.send`) | One-tap sign-in | A Google Cloud project and OAuth client; Google verification for a public release (sensitive scope) | Later, for a Play Store release |
| C. Our own relay server | None | Running a server that sees every digest | Rejected: breaks the privacy model |

**Decision.**
- Option A, **off by default**. Presets cover Gmail, Outlook, Yahoo and Zoho, plus a custom server.
- Only TLS connections are allowed (SSL on 465 or STARTTLS on 587), with server identity checked. Plain-text SMTP exists only for unit tests and is not offered in the UI.
- The app password is encrypted with a non-exportable AES-256-GCM key in the **Android Keystore** and never logged. "Forget account" deletes it.
- The phone connects **only to the SMTP host the user configured**, and **only to send the digest**. No LOCAL AI server is involved. The recipient defaults to the user's own address.
- If sending fails (wrong password, offline), the notification says why and keeps the manual **Email** button.
- "Send test email" verifies the setup immediately.

**Consequences.** The privacy promise changes from "network only for model downloads" to "network only for model downloads and, if you turn it on, sending your digest to your own mail server". The Settings screen states this next to the switch. JavaMail for Android (`com.sun.mail:android-mail` 1.6.7) adds about 0.3 MB.
