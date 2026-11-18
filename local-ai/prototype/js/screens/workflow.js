/* Supervisor workflow: a small discrete-time simulation of the hybrid scheduler described in
   docs/AGENT_ARCHITECTURE.md §3.2 — LLM phases share ONE model slot, tool phases run alongside. */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc;
  LA.screens = LA.screens || {};

  const SPEC = [
    { id: 'sup', name: 'Supervisor', role: 'Routes the request', agent: null, deps: [], x: 180, y: 38, phases: [['llm', 1.6, 'Classifying request: needs a plan']], out: 'Request needs research, project context and code. Handing to planner.' },
    { id: 'plan', name: 'Planner', role: 'Builds a task DAG', agent: null, deps: ['sup'], x: 180, y: 112, phases: [['llm', 2.6, 'Writing plan JSON under grammar']], out: '{ "tasks": [ "t1 research", "t2 knowledge", "t3 coding ← t1,t2", "t4 review ← t3" ] }' },
    { id: 'res', name: 'Atlas', role: 'Research Agent', agent: 'ag-atlas', deps: ['plan'], x: 90, y: 196, phases: [['llm', 1.6, 'Choosing search queries'], ['tool', 1.8, 'knowledge_search ×2'], ['llm', 3.0, 'Summarising trade-offs']], out: 'llama.cpp: GGUF, broad models, best CPU path, no NPU. ExecuTorch: NPU via QNN, per-model export. [chunk:21][chunk:44]' },
    { id: 'kn', name: 'Archivist', role: 'Knowledge Agent', agent: 'ag-archivist', deps: ['plan'], x: 270, y: 196, phases: [['llm', 1.2, 'Querying project docs'], ['tool', 1.4, 'knowledge_search'], ['llm', 2.0, 'Extracting constraints']], out: 'Constraints: one engine behind the scheduler; minSdk 31; arm64 only; Flow-based streaming. [chunk:31]' },
    { id: 'code', name: 'Forge', role: 'Coding Agent', agent: 'ag-forge', deps: ['res', 'kn'], x: 180, y: 284, phases: [['llm', 3.6, 'Drafting InferenceEngine interface'], ['tool', 1.0, 'code_search'], ['llm', 2.2, 'Aligning with InferenceScheduler']], out: 'interface InferenceEngine { fun generate(req): Flow<TokenEvent>; suspend fun embed(texts): List<FloatArray> }' },
    { id: 'rev', name: 'Critic', role: 'Review Agent', agent: 'ag-reviewer', deps: ['code'], x: 180, y: 364, phases: [['llm', 2.4, 'Checking draft against sources']], out: 'OK with one note: add abort() semantics to the interface docs.' },
    { id: 'agg', name: 'Aggregator', role: 'Composes the answer', agent: null, deps: ['rev'], x: 180, y: 438, always: true, phases: [['llm', 2.4, 'Writing final response']], out: 'Final answer with recommendation, interface draft and 3 citations.' },
    { id: 'out', name: 'Final response', role: 'Delivered to chat', agent: null, deps: ['agg'], x: 180, y: 506, always: true, phases: [], out: '' }
  ];
  const ORDER = SPEC.map((s) => s.id);
  const W = { status: 'idle', nodes: {}, log: [], slot: null, t: 0, speed: 1, fail: 'none', timer: null, used: { steps: 0, tools: 0, tokens: 0 }, request: 'Compare llama.cpp and ExecuTorch for our app and draft a Kotlin interface for the inference engine.', onDone: null };
  LA.wf = W;

  W.reset = function () {
    clearInterval(W.timer); W.timer = null; W.status = 'idle'; W.t = 0; W.slot = null; W.log = []; W.used = { steps: 0, tools: 0, tokens: 0 };
    SPEC.forEach((s) => { W.nodes[s.id] = { state: 'pending', phase: -1, left: 0, attempt: 1, needSlot: false, detail: '', started: null, ended: null }; });
    W.dirty = true;
  };
  const log = (txt, kind) => { W.log.unshift({ t: W.t, txt, kind: kind || 'info' }); W.dirty = true; };
  const spec = (id) => SPEC.find((s) => s.id === id);

  function startPhase(id) {
    const n = W.nodes[id]; const s = spec(id); const ph = s.phases[n.phase];
    n.left = ph[1]; n.detail = ph[2]; n.needSlot = ph[0] === 'llm';
    if (ph[0] === 'tool') { W.used.tools += ph[2].includes('×2') ? 2 : 1; log(`${s.name} → tool ${ph[2]} (runs while the model serves others)`, 'tool'); }
  }
  function finishNode(id, ok) {
    const n = W.nodes[id]; const s = spec(id);
    n.state = ok ? 'completed' : 'failed'; n.ended = W.t; n.needSlot = false; n.detail = ok ? 'Done' : 'Failed after ' + n.attempt + ' attempts';
    log(ok ? `${s.name} completed` : `${s.name} failed · dependents will be skipped`, ok ? 'ok' : 'bad');
    if (!ok) skipDependents(id);
  }
  function skipDependents(id) {
    SPEC.filter((s) => s.deps.includes(id) && !s.always).forEach((s) => { if (W.nodes[s.id].state === 'pending') { W.nodes[s.id].state = 'skipped'; W.nodes[s.id].detail = 'Skipped: input failed'; log(`${s.name} skipped`, 'muted'); skipDependents(s.id); } });
  }
  function advance(id) {
    const n = W.nodes[id]; const s = spec(id);
    // Failure injection on the coding agent's first LLM phase
    if (id === 'code' && n.phase === 0 && W.fail !== 'none' && n.attempt <= (W.fail === 'permanent' ? 2 : 1)) {
      if (W.fail === 'permanent' && n.attempt === 2) { finishNode(id, false); return; }
      log(`Forge returned invalid JSON (attempt ${n.attempt}) · retrying with a repair prompt`, 'bad');
      n.attempt += 1; n.state = 'failed'; n.detail = 'Retrying…'; n.retryAt = W.t + 0.8; n.needSlot = false; return;
    }
    n.phase += 1;
    if (n.phase >= s.phases.length) { finishNode(id, true); return; }
    startPhase(id);
  }
  const depsReady = (s) => s.deps.every((d) => { const st = W.nodes[d].state; return st === 'completed' || (s.always && (st === 'failed' || st === 'skipped')); });

  function step(dt) {
    W.t += dt;
    // retries
    SPEC.forEach((s) => { const n = W.nodes[s.id]; if (n.retryAt && W.t >= n.retryAt) { n.retryAt = null; n.state = 'running'; n.phase = 0; startPhase(s.id); } });
    // tool phases progress in parallel
    SPEC.forEach((s) => { const n = W.nodes[s.id]; if (n.state === 'running' && !n.needSlot && n.phase >= 0 && s.phases[n.phase] && s.phases[n.phase][0] === 'tool') { n.left -= dt; if (n.left <= 0) advance(s.id); } });
    // LLM slot holder progresses
    if (W.slot) {
      const n = W.nodes[W.slot];
      n.left -= dt; W.used.tokens += Math.round(dt * (LA.device().genTps + 40));
      if (n.left <= 0) { const id = W.slot; W.slot = null; W.used.steps += 1; advance(id); }
    }
    // start pending nodes whose dependencies are done
    SPEC.forEach((s) => { const n = W.nodes[s.id]; if (n.state === 'pending' && depsReady(s)) { n.state = 'running'; n.started = W.t; if (!s.phases.length) { finishNode(s.id, true); return; } n.phase = 0; startPhase(s.id); log(`${s.name} started${s.agent ? ' · ' + s.role : ''}`, 'run'); } });
    // assign the single model slot (topological order = priority)
    if (!W.slot) {
      const next = ORDER.find((id) => W.nodes[id].state === 'running' && W.nodes[id].needSlot);
      if (next) { W.slot = next; if (W.nodes[next].phase > 0 || W.nodes[next].attempt > 1) log(`${spec(next).name} takes the model slot`, 'muted'); }
    }
    // finish
    if (W.nodes.out.state === 'completed') {
      const partial = SPEC.some((s) => W.nodes[s.id].state === 'failed' || W.nodes[s.id].state === 'skipped');
      W.status = partial ? 'partial' : 'done'; clearInterval(W.timer); W.timer = null;
      log(partial ? 'Finished with partial results · the coding step can be retried' : 'Workflow finished', partial ? 'bad' : 'ok');
      if (W.onDone) { W.onDone(partial ? '**Partial result.** Atlas and Archivist completed, but Forge could not produce a valid interface after 2 attempts, so review was skipped. Open the workflow and retry the coding step.\n\n**Recommendation:** llama.cpp first, ExecuTorch later for NPU [chunk:31].' : '**Recommendation:** start with **llama.cpp** for GGUF breadth and the strongest CPU path; add **ExecuTorch** later as an NPU backend for selected models [chunk:31].\n\n```kotlin\ninterface InferenceEngine {\n    fun generate(request: GenerationRequest): Flow<TokenEvent>\n    suspend fun embed(texts: List<String>): List<FloatArray>\n    suspend fun abort()\n}\n```\n\nReviewed by Critic: added `abort()` for cancellation.'); W.onDone = null; }
    }
    W.dirty = true;
  }

  W.start = function (o) {
    W.reset(); W.status = 'running'; if (o && o.request) W.request = o.request; W.onDone = (o && o.onDone) || null;
    log('Run started · budgets: 16 steps, 12 tool calls, 24k tokens, 5 min', 'info');
    W.timer = setInterval(() => step(0.1 * W.speed), 100);
  };
  W.pause = function () { if (W.status !== 'running') return; clearInterval(W.timer); W.timer = null; W.status = 'paused'; log('Paused by user', 'muted'); };
  W.resume = function () { if (W.status !== 'paused') return; W.status = 'running'; W.timer = setInterval(() => step(0.1 * W.speed), 100); log('Resumed', 'muted'); };
  W.cancel = function () {
    if (!['running', 'paused'].includes(W.status)) return;
    clearInterval(W.timer); W.timer = null; W.status = 'cancelled'; W.slot = null;
    SPEC.forEach((s) => { const n = W.nodes[s.id]; if (n.state === 'running') { n.state = 'cancelled'; n.detail = 'Cancelled'; } else if (n.state === 'pending') { n.state = 'skipped'; n.detail = 'Not started'; } });
    log('Cancelled · partial outputs kept', 'bad'); W.dirty = true;
    if (W.onDone) { W.onDone('Workflow cancelled. Completed steps are kept in the run history.'); W.onDone = null; }
  };
  W.retryCode = function () {
    const n = W.nodes.code; if (n.state !== 'failed') return;
    W.fail = 'none'; n.state = 'running'; n.attempt = 1; n.phase = 0; startPhase('code');
    ['rev', 'agg', 'out'].forEach((id) => { W.nodes[id] = { state: 'pending', phase: -1, left: 0, attempt: 1, needSlot: false, detail: '' }; });
    W.status = 'running'; log('Retrying Forge from the start', 'run');
    W.timer = setInterval(() => step(0.1 * W.speed), 100);
  };
  W.summary = function () {
    const map = { completed: 'done', running: 'run', failed: 'fail', skipped: '', pending: '', cancelled: 'fail' };
    const done = SPEC.filter((s) => W.nodes[s.id].state === 'completed').length;
    const label = { idle: 'Not started', running: `Running · ${done}/${SPEC.length}`, paused: 'Paused', done: 'Completed', partial: 'Partial result', cancelled: 'Cancelled' }[W.status];
    return { status: W.status, label, nodes: SPEC.map((s) => map[W.nodes[s.id].state]) };
  };
  W.reset();

  /* ---------- rendering ---------- */
  function graph() {
    const NW = 132, NH = 46;
    const edges = SPEC.flatMap((s) => s.deps.map((d) => {
      const a = spec(d); const st = W.nodes[s.id].state; const ps = W.nodes[d].state;
      const cls = st === 'completed' ? 'done' : st === 'running' && ps === 'completed' ? 'active' : st === 'skipped' || ps === 'failed' ? 'fail' : '';
      const x1 = a.x, y1 = a.y + NH / 2, x2 = s.x, y2 = s.y - NH / 2, my = (y1 + y2) / 2;
      return `<path class="edge ${cls}" d="M${x1} ${y1} C${x1} ${my} ${x2} ${my} ${x2} ${y2}"/>`;
    })).join('');
    const nodes = SPEC.map((s) => {
      const n = W.nodes[s.id]; const holds = W.slot === s.id;
      const sub = n.state === 'running' ? (holds ? '● ' : n.needSlot ? '◌ waiting for model · ' : '⚙ ') + n.detail : n.state === 'pending' ? s.role : n.detail || s.role;
      return `<g class="node ${n.state}" data-action="node" data-id="${s.id}" tabindex="0" role="button" aria-label="${esc(s.name)}: ${n.state}"><rect x="${s.x - NW / 2}" y="${s.y - NH / 2}" width="${NW}" height="${NH}" rx="12"/><circle class="st" cx="${s.x - NW / 2 + 14}" cy="${s.y - 7}" r="4"/><text x="${s.x - NW / 2 + 24}" y="${s.y - 3}">${esc(s.name)}${n.attempt > 1 && s.id === 'code' ? ' · try ' + n.attempt : ''}</text><text class="sub" x="${s.x - NW / 2 + 12}" y="${s.y + 13}">${esc(sub.length > 22 ? sub.slice(0, 21) + '…' : sub)}</text></g>`;
    }).join('');
    return `<svg class="graph" viewBox="0 0 360 540" role="img" aria-label="Workflow graph">${edges}${nodes}</svg>`;
  }
  function budgets() {
    const B = [['LLM steps', W.used.steps, 16], ['Tool calls', W.used.tools, 12], ['Tokens', W.used.tokens, 24000], ['Time', Math.round(W.t), 300]];
    return `<div class="budget">${B.map(([k, v, m]) => `<div class="b"><div class="row between"><span>${k}</span><span class="tnum">${k === 'Tokens' ? (v / 1000).toFixed(1) + 'k' : v}${k === 'Time' ? ' s' : ''} / ${k === 'Tokens' ? '24k' : m}${k === 'Time' ? ' s' : ''}</span></div>${C.progress(v / m, v / m > 0.8 ? 'warn thin' : 'thin')}</div>`).join('')}</div>`;
  }
  function slotLine() {
    const m = LA.model(LA.S.activeModel);
    const holder = W.slot ? spec(W.slot) : null;
    const tools = SPEC.filter((s) => W.nodes[s.id].state === 'running' && !W.nodes[s.id].needSlot && s.phases[W.nodes[s.id].phase] && s.phases[W.nodes[s.id].phase][0] === 'tool');
    return `<div class="slot">${holder ? '<span class="spin"></span>' : I('models', 'icon-sm')}<div class="grow"><b>${esc(m.name)}</b> · ${holder ? 'serving <b>' + esc(holder.name) + '</b>' : W.status === 'running' ? 'idle' : 'one shared model'}${tools.length ? `<div class="xs faint">Tools running in parallel: ${tools.map((t) => esc(t.name)).join(', ')}</div>` : ''}</div></div>`;
  }
  LA.screens.workflow = {
    render() {
      const s = W.status; const sm = W.summary();
      const ctrl = s === 'running' ? `<button class="btn btn-outline btn-sm" data-action="pause">${I('pause', 'icon-sm')}Pause</button><button class="btn btn-danger btn-sm" data-action="cancel">Cancel</button>`
        : s === 'paused' ? `<button class="btn btn-tonal btn-sm" data-action="resume">${I('play', 'icon-sm')}Resume</button><button class="btn btn-danger btn-sm" data-action="cancel">Cancel</button>`
          : `<button class="btn btn-primary btn-sm" data-action="run">${I('play', 'icon-sm')}${s === 'idle' ? 'Run' : 'Run again'}</button>${W.nodes.code.state === 'failed' && s === 'partial' ? `<button class="btn btn-tonal btn-sm" data-action="retry">${I('refresh', 'icon-sm')}Retry coding step</button>` : ''}`;
      return `${C.topbar({ title: 'Workflow', sub: 'Supervisor · ' + sm.label, back: 'agents', actions: C.ib('sliders', 'opts', 'Simulation options') })}
      <div class="scroll"><div class="body">
        <div class="card tight"><div class="eyebrow">Request</div><div class="small" style="margin-top:4px">${esc(W.request)}</div></div>
        <div class="row between wrap" style="gap:8px"><div class="row" style="gap:8px">${ctrl}</div>${C.sample('Simulation')}</div>
        <div class="row wrap" style="gap:6px">${['pending', 'running', 'completed', 'failed', 'skipped'].map((x) => C.status(x)).join('')}</div>
        <div id="wf-live">${slotLine()}</div>
        <div class="graph-wrap" id="wf-graph">${graph()}</div>
        <div class="card"><div class="row between" style="margin-bottom:10px"><b>Budgets</b><span class="xs faint">limits from AGENT_ARCHITECTURE §4</span></div><div id="wf-budget">${budgets()}</div></div>
        <div class="card"><b>Execution log</b><div class="log" id="wf-log" style="margin-top:8px">${logHtml()}</div></div>
        <p class="xs faint" style="margin:0">Tap any node to see its instruction and output. LLM steps take turns on the one loaded model; tool calls overlap with them.</p>
      </div></div>`;
    },
    tick() {
      if (!W.dirty) return; W.dirty = false;
      const g = document.getElementById('wf-graph'); if (!g) return;
      g.innerHTML = graph(); document.getElementById('wf-budget').innerHTML = budgets(); document.getElementById('wf-log').innerHTML = logHtml(); document.getElementById('wf-live').innerHTML = slotLine();
      const sub = document.querySelector('.topbar h1 small'); if (sub) sub.textContent = 'Supervisor · ' + W.summary().label;
      if (W._lastStatus !== W.status) { W._lastStatus = W.status; LA.refresh(); }
    },
    mount() { W._lastStatus = W.status; },
    actions: {
      run: () => { W.start({ request: W.request }); LA.refresh(); },
      pause: () => { W.pause(); LA.refresh(); },
      resume: () => { W.resume(); LA.refresh(); },
      cancel: () => { W.cancel(); LA.refresh(); },
      retry: () => { W.retryCode(); LA.refresh(); },
      opts: () => LA.sheet({
        title: 'Simulation options', body: `<div class="field"><span class="label">Speed</span>${C.seg('Speed', [['1', '1×'], ['2', '2×'], ['4', '4×']], String(W.speed), 'speed', true)}</div><div class="field"><span class="label">Inject a failure in the coding step</span>${C.seg('Failure', [['none', 'None'], ['transient', 'Retry succeeds'], ['permanent', 'Fails twice']], W.fail, 'fail', true)}<span class="hint">Shows retry with a repair prompt, or a failed step whose dependents are skipped while the aggregator still returns a partial answer.</span></div>`,
        actions: '<button class="btn btn-primary" data-action="close-sheet">Done</button>',
        handlers: {
          speed: (b) => { W.speed = +b.getAttribute('data-v'); b.parentNode.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); if (W.timer) { clearInterval(W.timer); W.timer = setInterval(() => step(0.1 * W.speed), 100); } },
          fail: (b) => { W.fail = b.getAttribute('data-v'); b.parentNode.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); }
        }
      }),
      node: (el) => {
        const s = spec(el.getAttribute('data-id')); const n = W.nodes[s.id]; const a = s.agent ? LA.agent(s.agent) : null;
        LA.sheet({
          title: esc(s.name), body: `<div class="row" style="gap:12px">${a ? C.avatar(a) : `<span class="avatar" style="background:var(--surface-3)">${I('workflow')}</span>`}<div class="grow"><b>${esc(s.role)}</b><div class="small muted">${a ? 'Agent · ' + a.tools.map((t) => LA.tool(t).name).join(', ') : 'System step'}</div></div>${C.status(n.state)}</div>
            <dl class="kv"><dt>Depends on</dt><dd>${s.deps.map((x) => esc(spec(x).name)).join(', ') || '—'}</dd><dt>Attempts</dt><dd>${n.attempt}</dd><dt>Phases</dt><dd>${s.phases.map((p) => (p[0] === 'llm' ? 'LLM' : 'Tool') + ' ' + p[1] + 's').join(' → ') || '—'}</dd></dl>
            <div class="field"><span class="label">Output</span><pre class="json" style="white-space:pre-wrap">${n.state === 'completed' ? esc(s.out || 'Delivered to the conversation.') : n.state === 'failed' ? 'Invalid output. Error returned to the planner.' : 'Not available yet.'}</pre></div>`
        });
      }
    }
  };
  function logHtml() {
    if (!W.log.length) return '<div class="small muted">Press Run to start the simulated workflow.</div>';
    const col = { ok: 'var(--ok)', bad: 'var(--bad)', tool: 'var(--hue-knowledge)', run: 'var(--accent-strong)', muted: 'var(--text-3)', info: 'var(--text-2)' };
    return W.log.slice(0, 40).map((e) => `<div class="ev"><time>${e.t.toFixed(1)}s</time><span style="color:${col[e.kind]}">${esc(e.txt)}</span></div>`).join('');
  }
})();
