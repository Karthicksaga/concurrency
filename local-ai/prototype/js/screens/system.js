/* Tool registry, settings and device performance monitor. */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc; const F = LA.fmt;
  LA.screens = LA.screens || {};

  /* ---------- tools ---------- */
  let ttab = 'tools';
  const RISK = { READ: ['Read only', 'kb'], WRITE_LOCAL: ['Changes local data', 'agent'], DELEGATE: ['Delegates to agents', 'model'] };
  LA.screens.tools = {
    render() {
      const S = LA.S;
      const toolsHtml = S.tools.map((t) => {
        const used = S.agents.filter((a) => a.tools.includes(t.id));
        return `<div class="card"><div class="row" style="align-items:flex-start"><span class="avatar sm" style="background:var(--surface-3);color:var(--text-2)">${I(t.icon)}</span><div class="grow"><div class="row between wrap" style="gap:6px"><b>${esc(t.name)}</b><span class="tag ${RISK[t.risk][1]}">${RISK[t.risk][0]}</span></div><div class="small muted" style="margin-top:2px">${esc(t.desc)}</div></div></div>
          <div class="row between wrap" style="margin-top:12px;gap:8px"><span class="xs faint">Used by ${used.length ? used.map((a) => esc(a.name)).join(', ') : 'no agents'}</span>${C.seg('Permission for ' + t.name, [['ALLOWED', 'Allow'], ['ASK', 'Ask'], ['OFF', 'Off']], t.permission, 'perm', false).replace(/data-action="perm"/g, `data-action="perm" data-id="${t.id}"`)}</div>
          <details class="disclosure" style="margin-top:10px"><summary class="small">Input schema<span class="chev">${I('down', 'icon-sm')}</span></summary><pre class="json" style="margin-top:8px">${esc(JSON.stringify(t.schema, null, 2))}</pre></details>
          ${t.id === 'calculator' ? `<div class="row" style="margin-top:10px;gap:8px"><input class="input grow" id="calc-in" placeholder="(1250 * 12) * 0.18" aria-label="Try the calculator"><button class="btn btn-tonal btn-sm" data-action="calc">Run</button></div><div id="calc-out" class="small mono" style="margin-top:8px;min-height:18px" aria-live="polite"></div>` : ''}</div>`;
      }).join('');
      const logHtml = `<div class="list">${S.toolLog.map((l) => { const a = LA.agent(l.agent); const st = { OK: 'completed', TIMEOUT: 'failed', INVALID: 'failed', DENIED: 'cancelled' }[l.status]; return `<div class="li" style="align-items:flex-start">${I(LA.tool(l.tool).icon)}<div class="grow"><div class="row between"><b class="small">${esc(LA.tool(l.tool).name)}</b>${C.status(st, l.status === 'OK' ? 'OK' : l.status === 'TIMEOUT' ? 'Timed out' : 'Rejected')}</div><div class="mono" style="color:var(--text-2);margin-top:4px;overflow-wrap:anywhere">${esc(l.args)}</div><div class="xs faint" style="margin-top:4px">${a ? esc(a.name) : 'Agent'} · ${l.ms} ms · ${F.ago(l.at)}${l.confirmed ? ' · confirmed by you' : ''}${l.note ? ' · ' + esc(l.note) : ''}</div></div></div>`; }).join('')}</div>`;
      return `${C.topbar({ title: 'Tool registry', sub: S.tools.length + ' built-in tools · typed and validated', back: 'agents' })}
      <div class="scroll"><div class="body">
        <div class="banner info">${I('shield')}<span>Agents can only call these registered tools. Inputs are generated under a grammar, then validated again. No tool runs code, opens the internet or changes permissions.</span></div>
        ${C.seg('View', [['tools', 'Tools'], ['log', 'Audit log']], ttab, 'ttab', true)}
        <div class="col" style="gap:12px">${ttab === 'tools' ? toolsHtml : logHtml}</div>
      </div></div>`;
    },
    actions: {
      ttab: (el) => { ttab = el.getAttribute('data-v'); LA.refresh(); },
      perm: (el) => { const t = LA.tool(el.getAttribute('data-id')); t.permission = el.getAttribute('data-v'); LA.save(); LA.refresh(); LA.toast(esc(t.name) + ': ' + { ALLOWED: 'allowed', ASK: 'asks every time', OFF: 'turned off' }[t.permission]); },
      calc: () => {
        const v = document.getElementById('calc-in').value; const out = document.getElementById('calc-out');
        try { const r = LA.calc(v); out.style.color = 'var(--ok)'; out.textContent = '= ' + (+r.toFixed(8)).toLocaleString('en-US'); LA.S.toolLog.unshift({ tool: 'calculator', agent: null, args: JSON.stringify({ expression: v }), status: 'OK', ms: 1, at: Date.now() }); LA.save(); }
        catch (e) { out.style.color = 'var(--bad)'; out.textContent = 'ToolError: ' + e.message; }
      }
    }
  };

  /* ---------- settings ---------- */
  LA.screens.prefs = {
    render() {
      const S = LA.S; const st = S.settings; const d = LA.device();
      const row = (t, s, ctl) => `<div class="li"><div class="grow"><div class="t small">${t}</div>${s ? `<div class="s">${s}</div>` : ''}</div>${ctl}</div>`;
      return `${C.topbar({ title: 'Settings', back: 'home' })}
      <div class="scroll"><div class="body">
        ${C.sectionH('Appearance')}<div class="list">${row('Theme', 'Dark is the default. Light is available for bright environments.', C.seg('Theme', [['dark', 'Dark'], ['light', 'Light']], S.look, 'look'))}</div>
        ${C.sectionH('Device')}<div class="list">${row('Simulated device', 'Prototype only: switches the hardware profile used for compatibility checks.', '')}<div class="li" style="padding-top:0">${C.seg('Device RAM', [['4', '4 GB'], ['6', '6 GB'], ['8', '8 GB'], ['12', '12 GB'], ['16', '16 GB']], S.deviceId, 'dev', true)}</div>${d.lite ? `<div class="li" style="padding-top:0"><div class="banner info" style="width:100%">${I('info')}<span><b>Lite mode</b> is on for this phone: 1–2B models, 2K context, smaller batches, model released 30–120 s after leaving the app, and multi-agent workflows limited to 3 steps.</span></div>` : ''}${row(esc(d.soc), d.cpu + ' · ' + d.features, `<button class="link" data-go="monitor">Monitor</button>`)}</div>
        ${C.sectionH('Inference')}<div class="list">
          ${row('CPU threads', 'Auto uses the ' + d.perfCores + ' performance cores and backs off when hot.', C.seg('Threads', [['auto', 'Auto'], ['4', '4'], ['6', '6']], st.threads, 'threads'))}
          ${row('Default KV cache', 'q8_0 halves context memory.', C.seg('KV cache', [['f16', 'f16'], ['q8_0', 'q8_0']], st.kvType, 'kv'))}
          ${row('GPU acceleration (experimental)', d.gpu + ' via OpenCL. Enabled only after a self-test passes.', C.sw('s-gpu', st.gpu, 'gpu', 'GPU acceleration'))}
          ${row('Unload model when idle', 'After 5 minutes in the background.', C.sw('s-idle', true, 'noop', 'Unload when idle'))}</div>
        ${C.sectionH('Knowledge & memory')}<div class="list">
          ${row('Index only while charging', 'Pauses background indexing on battery.', C.sw('s-charge', st.indexOnCharge, 'charge', 'Index only while charging'))}
          ${row('Approve new memories', 'Agents propose memories; you decide what is saved.', C.sw('s-mem', st.memoryApproval, 'memappr', 'Approve new memories'))}</div>
        ${C.sectionH('Privacy')}<div class="list">
          ${row('Internet access', 'Only for model downloads and catalog updates.', '<span class="status ok">Downloads only</span>')}
          ${row('Analytics and crash reporting', 'None. Nothing is collected.', '<span class="status pending">Off</span>')}
          ${row('App lock', 'Require fingerprint or PIN to open.', C.sw('s-lock', st.appLock, 'lock', 'App lock'))}
          <button class="li" data-action="netlog">${I('signal')}<div class="grow"><div class="t small">Network log</div><div class="s">Every connection this app made</div></div>${I('chevron', 'icon-sm')}</button></div>
        ${C.sectionH('Storage')}<div class="card">${storage()}</div>
        ${C.sectionH('Data')}<div class="list"><button class="li" data-action="wipe" style="color:var(--bad)">${I('trash')}<div class="grow"><div class="t small">Erase everything</div><div class="s">Models, documents, chats, agents and memories</div></div></button></div>
        ${C.sectionH('Prototype')}<div class="list"><button class="li" data-action="restore">${I('refresh')}<div class="grow"><div class="t small">Restore demo data</div><div class="s">Resets every change you made in this prototype</div></div></button><button class="li" data-action="screens">${I('grid')}<div class="grow"><div class="t small">Screen index</div><div class="s">Jump to any of the 17 prototype screens</div></div></button><button class="li" data-action="onb">${I('sparkle')}<div class="grow"><div class="t small">Replay onboarding</div></div></button></div>
        <p class="xs faint" style="text-align:center;margin:0">LOCAL AI · UI prototype · sample data only</p>
      </div></div>`;
    },
    changes: {
      gpu: (el) => { LA.S.settings.gpu = el.checked; LA.save(); LA.toast(el.checked ? 'GPU will be tested on next model load (simulated)' : 'Using CPU'); },
      charge: (el) => { LA.S.settings.indexOnCharge = el.checked; LA.save(); }, memappr: (el) => { LA.S.settings.memoryApproval = el.checked; LA.save(); },
      lock: (el) => { LA.S.settings.appLock = el.checked; LA.save(); }, noop: () => {}
    },
    actions: {
      look: (el) => { LA.S.look = el.getAttribute('data-v'); LA.applyLook(); LA.save(); LA.refresh(); },
      dev: (el) => { LA.S.deviceId = el.getAttribute('data-v'); LA.S.profiles = {}; LA.save(); LA.refresh(); LA.toast('Simulating ' + LA.device().label); },
      threads: (el) => { LA.S.settings.threads = el.getAttribute('data-v'); LA.save(); LA.refresh(); },
      kv: (el) => { LA.S.settings.kvType = el.getAttribute('data-v'); LA.S.profiles = {}; LA.save(); LA.refresh(); },
      netlog: () => LA.sheet({ title: 'Network log', body: `<p class="small muted" style="margin:0">Sample entries. The app records host, path and size of every request for 30 days.</p><div class="list">${LA.S.networkLog.map((n) => `<div class="li" style="align-items:flex-start">${I('download')}<div class="grow"><div class="t small">${esc(n.host)}</div><div class="mono" style="color:var(--text-2);overflow-wrap:anywhere">${esc(n.path)}</div><div class="xs faint">${esc(n.why)} · ${n.bytes} · ${F.ago(n.at)}</div></div></div>`).join('')}</div>` }),
      wipe: () => LA.confirm({ title: 'Erase everything?', body: 'All models, documents, conversations, agents and memories are permanently deleted from this phone. In this prototype it restores the demo data and restarts onboarding.', ok: 'Erase everything', danger: true, onOk: () => { LA.S = LA.seed(); LA.wf.reset(); LA.save(); LA.applyLook(); LA.go('welcome'); } }),
      restore: () => LA.confirm({ title: 'Restore demo data?', body: 'Your changes in this prototype will be lost.', ok: 'Restore', onOk: () => LA.reset() }),
      onb: () => { LA.S.onboarded = false; LA.save(); LA.go('welcome'); },
      screens: () => LA.sheet({ title: 'Screen index', body: `<div class="list">${LA.SCREENS.map(([h, t], i) => `<button class="li" data-go="${h}"><span class="mono faint">${String(i + 1).padStart(2, '0')}</span><div class="grow t small">${t}</div>${I('chevron', 'icon-sm')}</button>`).join('')}</div>` })
    }
  };
  function storage() {
    const S = LA.S; const d = LA.device();
    const models = S.models.filter((m) => m.state === 'installed').reduce((s, m) => s + m.sizeGB, 0);
    const docs = S.kbs.reduce((s, k) => s + LA.kbStats(k).mb, 0) / 1024;
    const idx = docs * 0.35; const cache = 0.18;
    const used = models + docs + idx + cache; const tot = used + d.freeStorageGB;
    return `<div class="row between"><span class="eyebrow">Used by LOCAL AI</span><b class="tnum">${used.toFixed(2)} GB</b></div><div class="stack" style="margin-top:12px">${[[models, 'var(--hue-model)'], [docs, 'var(--hue-knowledge)'], [idx, 'var(--hue-agent)'], [cache, 'var(--text-3)']].map(([v, c]) => `<i style="width:${Math.max(0.6, (v / tot) * 100)}%;background:${c}"></i>`).join('')}</div>
      <div class="legend" style="margin-top:10px"><span style="--c:var(--hue-model)">Models ${models.toFixed(2)} GB</span><span style="--c:var(--hue-knowledge)">Documents ${F.mb(docs * 1024)}</span><span style="--c:var(--hue-agent)">Indexes ${F.mb(idx * 1024)}</span><span style="--c:var(--text-3)">Prompt cache ${F.mb(cache * 1024)}</span></div>
      <div class="row between" style="margin-top:12px"><span class="small muted">${d.freeStorageGB.toFixed(1)} GB free on device</span><button class="btn btn-ghost btn-sm" data-action="clear-cache">Clear cache</button></div>`;
  }
  LA.screens.prefs.actions['clear-cache'] = () => LA.toast('Prompt cache cleared (simulated)');

  /* ---------- performance monitor ---------- */
  const M = { tps: [], ram: [], temp: 36.5, gen: true, timer: null, t: 0, battery: 78 };
  function sample() {
    const d = LA.device(); M.t += 1;
    const loaded = LA.modelState() === 'loaded';
    const hot = Math.max(0, M.temp - 41) * 0.08;
    const target = M.gen && loaded ? d.genTps * (1 - hot) * (0.92 + Math.random() * 0.16) : 0;
    M.tps.push(+target.toFixed(1)); if (M.tps.length > 40) M.tps.shift();
    M.temp += M.gen && loaded ? 0.12 + Math.random() * 0.08 - (M.temp > 43 ? 0.25 : 0) : -0.2; M.temp = Math.max(34, Math.min(46, M.temp));
    if (M.gen && loaded && M.t % 8 === 0) M.battery = Math.max(5, M.battery - 1);
  }
  LA.screens.monitor = {
    render() {
      const d = LA.device(); const m = LA.model(LA.S.activeModel); const loaded = LA.modelState() === 'loaded';
      if (!M.tps.length) for (let i = 0; i < 24; i++) sample();
      const est = loaded ? LA.estimate(m, LA.profile(m).ctx, LA.profile(m).kv) : null;
      const thermal = M.temp < 39 ? ['ok', 'Normal'] : M.temp < 42 ? ['warn', 'Moderate · threads reduced'] : ['bad', 'Severe · slowing down'];
      const cur = M.tps[M.tps.length - 1] || 0; const avg = M.tps.filter((x) => x).reduce((s, x, i, a) => s + x / a.length, 0);
      return `${C.topbar({ title: 'Performance', sub: esc(d.label) + ' · ' + esc(d.soc), back: 'home' })}
      <div class="scroll"><div class="body">
        <div class="row between wrap" style="gap:8px"><label class="row small" style="gap:10px">${C.sw('m-gen', M.gen, 'gen', 'Simulate generation')}<span>Simulate generation</span></label>${C.sample('Simulated telemetry')}</div>
        <div class="card"><div class="row between"><div><div class="eyebrow">Generation speed</div><div class="big-num" style="margin-top:6px" id="m-tps">${cur.toFixed(1)}<small>tok/s</small></div></div><div style="text-align:right"><div class="xs faint">Average</div><div class="tnum" style="font-weight:600" id="m-avg">${avg.toFixed(1)} tok/s</div><div class="xs faint" style="margin-top:4px">Prompt eval ≈ ${d.promptTps} tok/s</div></div></div><div id="m-spark" style="margin-top:12px">${LA.spark(M.tps, d.genTps * 1.3, 'Tokens per second')}</div><div class="row between xs faint"><span>40 s ago</span><span>now</span></div></div>
        <div class="card"><div class="row between"><b>Memory</b><span class="small muted tnum">${d.ramGB} GB total</span></div>
          ${est ? `<div class="stack" style="margin-top:12px"><i style="width:${(est.weights / d.ramGB) * 100}%;background:var(--hue-model)"></i><i style="width:${(est.kv / d.ramGB) * 100}%;background:var(--hue-agent)"></i><i style="width:${((est.compute + est.overhead) / d.ramGB) * 100}%;background:var(--text-3)"></i><i style="width:${(0.31 / d.ramGB) * 100}%;background:var(--accent)"></i></div><div class="legend" style="margin-top:10px"><span style="--c:var(--hue-model)">Weights (mmap) ${est.weights.toFixed(2)} GB</span><span style="--c:var(--hue-agent)">KV cache ${est.kv.toFixed(2)} GB</span><span style="--c:var(--text-3)">Compute ${(est.compute + est.overhead).toFixed(2)} GB</span><span style="--c:var(--accent)">App 0.31 GB</span></div>` : '<p class="small muted" style="margin:10px 0 0">No model loaded.</p>'}
          <div class="small muted" style="margin-top:10px">Memory pressure: <b style="color:var(--ok)">low</b> · last trim event: none</div></div>
        <div class="card"><div class="row between"><b>CPU cores</b><span class="xs faint">${d.perfCores} threads in use</span></div><div class="cores" id="m-cores" style="margin-top:12px">${cores()}</div></div>
        <div class="stats two"><div class="stat"><div class="k">Thermal</div><div class="v" id="m-temp"><span class="status ${thermal[0]}">${thermal[1]}</span></div><div class="xs faint" style="margin-top:6px" id="m-tempc">${M.temp.toFixed(1)} °C · headroom ${Math.max(0, (46 - M.temp) / 12).toFixed(2)}</div></div><div class="stat"><div class="k">Battery</div><div class="v" id="m-bat">${M.battery}%</div><div class="xs faint" style="margin-top:6px">≈ 0.9% per 1k tokens</div></div></div>
        <div class="card"><b>Adaptive behaviour</b><div class="list" style="margin-top:10px;border:0">${[['thermo', 'Moderate heat', 'Drop one thread, pause indexing'], ['alert', 'Severe heat', 'Two threads, show a banner'], ['close', 'Critical heat', 'Finish the token batch, unload the model'], ['battery', 'Battery below 15%', 'Pause background indexing, warn before workflows']].map(([i, t, s]) => `<div class="li" style="padding-left:0;padding-right:0">${I(i)}<div class="grow"><div class="t small">${t}</div><div class="s">${s}</div></div></div>`).join('')}</div></div>
      </div></div>`;
    },
    mount() { clearInterval(M.timer); M.timer = setInterval(() => { sample(); upd(); }, 1000); },
    unmount() { clearInterval(M.timer); M.timer = null; },
    changes: { gen: (el) => { M.gen = el.checked; } }
  };
  function cores() {
    const d = LA.device(); const busy = M.gen && LA.modelState() === 'loaded';
    return Array.from({ length: 8 }, (_, i) => { const perf = i < d.perfCores; const v = busy && perf ? 70 + Math.random() * 28 : 4 + Math.random() * 14; return `<div><i class="${perf ? '' : 'eff'}" style="height:${v}%"></i><span>${i}</span></div>`; }).join('');
  }
  function upd() {
    const d = LA.device(); const el = document.getElementById('m-spark'); if (!el) return;
    el.innerHTML = LA.spark(M.tps, d.genTps * 1.3, 'Tokens per second');
    const cur = M.tps[M.tps.length - 1] || 0; const nz = M.tps.filter((x) => x); const avg = nz.length ? nz.reduce((s, x) => s + x, 0) / nz.length : 0;
    document.getElementById('m-tps').innerHTML = cur.toFixed(1) + '<small>tok/s</small>'; document.getElementById('m-avg').textContent = avg.toFixed(1) + ' tok/s';
    document.getElementById('m-cores').innerHTML = cores();
    const thermal = M.temp < 39 ? ['ok', 'Normal'] : M.temp < 42 ? ['warn', 'Moderate · threads reduced'] : ['bad', 'Severe · slowing down'];
    document.getElementById('m-temp').innerHTML = `<span class="status ${thermal[0]}">${thermal[1]}</span>`;
    document.getElementById('m-tempc').textContent = M.temp.toFixed(1) + ' °C · headroom ' + Math.max(0, (46 - M.temp) / 12).toFixed(2);
    document.getElementById('m-bat').textContent = M.battery + '%';
  }
})();
