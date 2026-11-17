# LOCAL AI — Interactive UI Prototype

A clickable, high-fidelity prototype of all 17 screens of the LOCAL AI Android app. It is plain HTML, CSS and JavaScript with **no build step and no dependencies**, and it is deliberately isolated from the future Android codebase (see `docs/ADR/0010-isolated-web-prototype.md`).

> Everything runs on **mock data**. There is no model inference, download, embedding, database or agent execution. Simulated elements are labelled "Sample data" or "Simulated" in the UI.

## Launch

Any of these works:

```bash
# 1. Open the file directly (no server needed)
xdg-open local-ai/prototype/index.html        # macOS: open …, Windows: start …

# 2. Or serve it (recommended for phones on the same Wi-Fi)
cd local-ai/prototype && python3 -m http.server 8080
# then visit http://localhost:8080  (or http://<your-computer-ip>:8080 on a phone)
```

On a desktop browser the app is shown in a phone frame with a **screen index** on the left. On a phone (or any window narrower than 560 px) it runs full-screen. Hash links jump straight to a screen, e.g. `index.html#workflow`, `#model-qwen3-4b`, `#memory-ag-juno`.

Optional single-file build (CSS and JS inlined):

```bash
node local-ai/prototype/tools/bundle.mjs /tmp/local-ai-prototype.html
```

## Things to try

| Try this | Where |
|---|---|
| Switch the simulated phone between 8 / 12 / 16 GB and watch model verdicts change | Settings › Device, then Models |
| Drag the context-length slider and watch the memory estimate and verdict update | Model details |
| Pause / resume a download | Models › Downloads |
| Send a message; stop it mid-stream; regenerate; tap a citation | Chat (try "Lease deposit clause") |
| Turn on **Agent mode › Supervisor** in a chat and send a task | Chat composer |
| Run the workflow; open Simulation options to inject a transient or permanent failure; retry the failed step; cancel | Agents › Supervisor workflow |
| Create an agent from a template (try saving without a name first) | Agents › New agent |
| Create a knowledge base, add files (one is a scanned PDF that fails) and watch indexing | Knowledge › New knowledge base |
| Semantic search across all knowledge | Knowledge |
| Approve, pin, edit and delete agent memories | Agents › Memory |
| Change a tool's permission; try the safe calculator with `(1250*12)*0.18` and with `alert(1)` | Agents › Tool registry |
| Light theme, network log, erase everything | Settings |
| Live tok/s, cores, thermal and battery simulation | Home › Performance |

Changes persist in your browser's local storage. **Settings › Prototype › Restore demo data** resets everything.

## Structure

```
prototype/
├─ index.html              shell: phone frame, status bar, script order
├─ css/styles.css          design tokens (dark default, light via data-look) + all components
├─ js/
│  ├─ icons.js             inline stroke icon set
│  ├─ data.js              sample models, devices, agents, KBs, documents, memories, tools, chats
│  ├─ ui.js                formatters, memory estimator + device verdict, components, sheets,
│  │                        toasts, markdown renderer, safe calculator, sparkline
│  ├─ app.js               store (localStorage), hash router, event delegation, simulation ticker
│  └─ screens/             home (onboarding + home), chat, models, agents (+memory), workflow,
│                           knowledge, system (tools, settings, monitor)
├─ tests/smoke.mjs         Playwright smoke + interaction suite, captures screenshots
├─ tools/bundle.mjs        single-file bundler
└─ screenshots/            output of the test run
```

The memory estimator in `ui.js` implements the same formula as `docs/MODEL_RUNTIME_DESIGN.md §5–6`, and the workflow simulation in `screens/workflow.js` implements the hybrid scheduler of `docs/AGENT_ARCHITECTURE.md §3.2` (one shared model slot, tool calls in parallel).

## Tests

```bash
node local-ai/prototype/tests/smoke.mjs            # with screenshots (2× scale)
node local-ai/prototype/tests/smoke.mjs --no-shots # faster
```

Requires Playwright with Chromium (`npm i -D playwright && npx playwright install chromium` if not already available). The suite visits all 17 screens at phone width (412 px), fails on horizontal overflow or console errors, and exercises the interactions listed above.
