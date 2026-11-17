# ADR-0013: Folder sync, phone tools (dialer, SMS) and the daily digest

**Status:** Accepted (2026-11-17, requested by the product owner)

**Context.** The owner asked for agents that fetch documents from folders, place calls, read SMS and bank statements, and send a daily summary email.

**Decisions.**
1. **Folder sync:** a knowledge base can be linked to a folder through the Storage Access Framework, with persisted read-only permission. Sync imports new files, re-imports changed files (detected by last-modified time) and removes deleted ones. It runs on demand, every 6 hours (battery not low) and before the daily digest. At most 500 files, 4 levels deep.
2. **Calls:** the `phone_call` tool opens the **dialer** with the number filled in (`ACTION_DIAL`), and the user presses Call. The app does not request `CALL_PHONE`, because an agent placing calls unattended is unsafe (wrong number, prompt injection from documents). Contact names are resolved only if the user grants contacts permission. The tool defaults to "Ask".
3. **SMS:** the `sms_reader` tool and the digest read the inbox **read-only** with `READ_SMS`. The tool is off by default and the permission is requested only when the user enables it. SMS text is processed on the phone and never sent anywhere. Google Play allows `READ_SMS` only for default SMS apps, so a Play release will need another approach: user-shared messages, or a notification listener with a policy declaration. Sideloaded test builds keep it.
4. **Daily digest:** a WorkManager job at a user-chosen time summarises the last 24 hours (SMS if allowed, new documents, notes, workflow runs) with the on-device model. It saves the result as a chat and posts a notification with an **Email** action. That action opens the user's email app pre-filled, and **nothing is sent automatically**. Automatic sending would need email credentials and network access beyond model downloads, which breaks the offline promise (ADR-0008). It is not built and needs an explicit product decision.
5. Text from SMS and documents is passed to the model as data, with an instruction to ignore instructions inside it. Side-effecting tools such as the dialer always require a confirmation or a user action.

**Consequences.** Useful personal automation without new network access. Two new runtime permissions (SMS, contacts), both optional. A Play Store release needs the SMS approach revisited.
