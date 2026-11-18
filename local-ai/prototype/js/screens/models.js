/* Model library, model details, downloads and GGUF import (all simulated). */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc; const F = LA.fmt;
  LA.screens = LA.screens || {}; LA.tickers = LA.tickers || [];

  /* ---------- download simulation ---------- */
  LA.tickers.push(() => {
    let ch = false;
    LA.S.downloads.forEach((d) => {
      if (d.state !== 'running') return;
      const m = LA.model(d.modelId);
      d.speedMBs = 12 + Math.random() * 6;
      d.progress = Math.min(1, d.progress + (d.speedMBs * 0.5 * 9) / (m.sizeGB * 1024)); // ~9x sped-up for demo
      ch = true;
      if (d.progress >= 1) { d.state = 'verifying'; d.verifyAt = Date.now() + 1200; }
    });
    LA.S.downloads.forEach((d) => {
      if (d.state === 'verifying' && Date.now() > d.verifyAt) {
        d.state = 'done'; d.finished = Date.now(); const m = LA.model(d.modelId); m.state = 'installed'; ch = true;
        LA.toast(esc(m.name) + ' installed · checksum verified (simulated)');
      }
    });
    return ch;
  });
  LA.startDownload = function (m) {
    const d = LA.device();
    if (m.sizeGB * 1.1 + 1 > d.freeStorageGB) { LA.toast('Not enough storage. Free ' + (m.sizeGB * 1.1 + 1 - d.freeStorageGB).toFixed(1) + ' GB first.'); return; }
    m.state = 'downloading';
    LA.S.downloads = LA.S.downloads.filter((x) => x.modelId !== m.id);
    LA.S.downloads.unshift({ id: LA.uid('dl'), modelId: m.id, progress: 0, speedMBs: 14, state: 'running' });
    LA.save(); LA.toast('Download started · simulated');
  };
  const dlFor = (m) => LA.S.downloads.find((d) => d.modelId === m.id && d.state !== 'done' && d.state !== 'cancelled');

  function actionBtn(m, small) {
    const d = dlFor(m); const sz = small ? 'btn-sm' : '';
    if (m.state === 'installed') return m.id === LA.S.activeModel ? `<span class="status ok">Active</span>` : m.purpose === 'embed' ? `<span class="status pending">Installed</span>` : `<button class="btn btn-tonal ${sz}" data-action="activate" data-id="${m.id}">Use</button>`;
    if (d) return `<button class="btn btn-outline ${sz}" data-go="dl-progress" data-dlt="${d.id}">${d.state === 'paused' ? 'Paused' : d.state === 'verifying' ? 'Verifying' : Math.round(d.progress * 100) + '%'}</button>`;
    return `<button class="btn btn-primary ${sz}" data-action="download" data-id="${m.id}" aria-label="Download ${esc(m.name)}">${I('download')}${F.size(m.sizeGB)}</button>`;
  }
  LA.modelCard = function (m) {
    const v = LA.verdict(m); const d = dlFor(m);
    return `<div class="card" data-model="${m.id}"><div class="row" style="align-items:flex-start;gap:12px">
      <button class="grow" data-go="model-${m.id}" style="border:0;background:none;padding:0;text-align:left;min-width:0">
        <div class="row" style="gap:8px"><b style="font:600 15px/1.3 var(--font-display)" class="ellipsis">${esc(m.name)}</b></div>
        <div class="small muted" style="margin-top:3px">${F.params(m.params)} params · ${m.quant} · ${esc(m.arch)}</div></button>${actionBtn(m, true)}</div>
      <div class="row wrap" style="margin-top:12px;gap:6px">${C.verdictPill(v)}<span class="tag">${I('ram', 'icon-sm')}${LA.recRam(m)} GB+ RAM</span><span class="tag">${I('download', 'icon-sm')}${F.size(m.sizeGB)}</span><span class="tag">${F.ctx(m.ctxTrain)} ctx</span>${m.purpose === 'embed' ? `<span class="tag kb">${m.dim}-dim</span>` : ''}</div>
      ${d ? `<div style="margin-top:12px" data-dlp="${d.id}">${C.progress(d.progress, d.state === 'paused' ? 'warn' : '')}</div>` : ''}</div>`;
  };

  /* ---------- library ---------- */
  let tab = 'all'; let fits = false; let q = '';
  function filtered() {
    return LA.S.models.filter((m) => (tab === 'all' ? m.purpose === 'gen' : tab === 'installed' ? m.state === 'installed' : m.purpose === 'embed'))
      .filter((m) => !fits || LA.verdict(m).key !== 'bad')
      .filter((m) => !q || (m.name + ' ' + m.family + ' ' + m.tags.join(' ')).toLowerCase().includes(q.toLowerCase()));
  }
  LA.screens.models = {
    nav: 'models',
    render() {
      const n = LA.S.downloads.filter((d) => ['running', 'paused', 'verifying'].includes(d.state)).length;
      const list = filtered(); const d = LA.device();
      return `${C.topbar({ title: 'Models', sub: `${d.label} · ≈${LA.usableGB(d).toFixed(1)} GB model budget`, actions: C.go('download', 'dl-progress', 'Downloads', n || '') + C.ib('upload', 'import', 'Import GGUF file') })}
      <div class="scroll"><div class="body">
        ${d.lite ? `<div class="banner info">${I('info')}<span><b>Lite mode</b> · ${d.label}. Showing small models first, with a 2K context to save memory.</span></div>` : ''}
        <div class="search">${I('search')}<input id="model-search" placeholder="Search models" value="${esc(q)}" data-input="mq" aria-label="Search models"></div>
        <div class="row between wrap" style="gap:8px">${C.seg('Model type', [['all', 'Chat'], ['installed', 'Installed'], ['embed', 'Embedding']], tab, 'tab')}<button class="chip sm ${fits ? 'on' : ''}" data-action="fits">${I('check', 'icon-sm')}Fits this device</button></div>
        <div class="row between"><span class="small faint">${list.length} model${list.length === 1 ? '' : 's'} · curated GGUF catalog</span>${C.sample()}</div>
        <div class="col" id="model-list" style="gap:12px">${list.map(LA.modelCard).join('') || C.empty('models', 'No models match', 'Clear the filters or search for another name.')}</div>
        <button class="btn btn-outline" data-action="import">${I('upload')}Import a GGUF file</button>
      </div></div>`;
    },
    mount() { this._sig = LA.dlSig(); },
    tick() { const sig = LA.dlSig(); if (sig !== this._sig) { this._sig = sig; LA.refresh(); } else LA.updateDownloads(); },
    inputs: { mq: (el) => { q = el.value; document.getElementById('model-list').innerHTML = filtered().map(LA.modelCard).join('') || C.empty('models', 'No models match', 'Clear the filters or search for another name.'); } },
    actions: {
      tab: (el) => { tab = el.getAttribute('data-v'); LA.refresh(); },
      fits: () => { fits = !fits; LA.refresh(); },
      download: (el) => { const m = LA.model(el.getAttribute('data-id')); const v = LA.verdict(m); if (v.key === 'bad') { LA.confirm({ title: 'This model may not run well', body: `${esc(m.name)} needs about ${v.e.total.toFixed(1)} GB but this phone has a budget of ${v.u.toFixed(1)} GB. It may crash or be very slow. Download anyway?`, ok: 'Download anyway', onOk: () => { LA.startDownload(m); LA.refresh(); } }); return; } LA.startDownload(m); LA.refresh(); },
      activate: (el) => { LA.loadModel(el.getAttribute('data-id')); LA.refresh(); LA.toast('Loading model…'); },
      import: () => LA.importModelSheet()
    }
  };

  /* ---------- import ---------- */
  LA.importModelSheet = function () {
    const steps = ['Reading GGUF header (magic, version 3)', 'Architecture: llama · supported', 'Tokenizer and chat template found', 'Tensor sizes consistent with file size', 'Copying to private storage (1.12 GB)'];
    LA.sheet({
      title: 'Import a GGUF model',
      body: `<div class="banner warn">${I('info')}<span>Simulated. In the app this opens the Android file picker, validates the file and copies it into private storage so it can be memory-mapped.</span></div>
        <div class="card tight"><div class="row">${I('file')}<div class="grow"><b>my-finetune-1.5b-Q4_K_M.gguf</b><div class="small muted">1.12 GB · Downloads folder</div></div></div></div>
        <div class="col" id="imp-steps" style="gap:0">${steps.map((s, i) => `<div class="check-row" data-step="${i}"><span class="k">${s}</span><span class="spin"></span></div>`).join('')}</div>
        <p class="xs faint" style="margin:0">Imported models are marked “unverified” because no catalog checksum exists.</p>`,
      actions: '<button class="btn btn-ghost" data-action="close-sheet">Cancel</button><button class="btn btn-primary" id="imp-done" data-action="imp-done" disabled>Add model</button>',
      mount(w) {
        steps.forEach((_, i) => setTimeout(() => { const r = w.querySelector(`[data-step="${i}"] .spin`); if (r) r.outerHTML = `<span class="v" style="color:var(--ok)">${I('check', 'icon-sm')}</span>`; if (i === steps.length - 1) { const b = document.getElementById('imp-done'); if (b) b.disabled = false; } }, 450 * (i + 1)));
      },
      handlers: {
        'imp-done': () => {
          if (!LA.model('import-finetune')) LA.S.models.push({ id: 'import-finetune', name: 'my-finetune 1.5B (imported)', family: 'Custom', params: 1.5, quant: 'Q4_K_M', sizeGB: 1.12, arch: 'llama', ctxTrain: 8192, layers: 24, kvHeads: 8, headDim: 64, license: 'Unknown · unverified', purpose: 'gen', state: 'installed', tags: ['Imported'], desc: 'Imported from local storage. Not checksum-verified against the catalog.' });
          LA.save(); LA.closeSheet(); LA.go('model-import-finetune'); LA.toast('Model imported (simulated)');
        }
      }
    });
  };

  /* ---------- downloads ---------- */
  LA.screens.downloads = {
    render() {
      const act = LA.S.downloads.filter((d) => ['running', 'paused', 'verifying'].includes(d.state));
      const done = LA.S.downloads.filter((d) => d.state === 'done');
      const dev = LA.device();
      const installed = LA.S.models.filter((m) => m.state === 'installed').reduce((s, m) => s + m.sizeGB, 0);
      const pending = act.reduce((s, d) => s + LA.model(d.modelId).sizeGB * (1 - d.progress), 0);
      const tot = dev.freeStorageGB + installed;
      const row = (d) => {
        const m = LA.model(d.modelId); const left = m.sizeGB * (1 - d.progress) * 1024; const eta = d.state === 'running' ? Math.ceil(left / d.speedMBs / 9) : null;
        return `<div class="card"><div class="row between" style="align-items:flex-start"><div class="grow"><b>${esc(m.name)}</b><div class="small muted">${m.quant} · ${F.size(m.sizeGB)} · huggingface.co</div></div>${d.state === 'running' ? C.status('running', 'Downloading') : d.state === 'verifying' ? C.status('running', 'Verifying SHA-256') : C.status('cancelled', 'Paused')}</div>
          <div style="margin-top:12px" data-dlp="${d.id}">${C.progress(d.progress, d.state === 'paused' ? 'warn' : '')}</div>
          <div class="row between small muted tnum" style="margin-top:8px"><span data-dlx="${d.id}">${(m.sizeGB * d.progress).toFixed(2)} of ${m.sizeGB.toFixed(2)} GB</span><span ${d.state === 'running' ? `data-dls="${d.id}"` : ''}>${d.state === 'running' ? d.speedMBs.toFixed(1) + ' MB/s · ~' + eta + ' s left (demo speed)' : d.state === 'paused' ? 'Resumes from ' + Math.round(d.progress * 100) + '%' : ''}</span></div>
          <div class="row" style="margin-top:12px;gap:8px">${d.state === 'running' ? `<button class="btn btn-outline btn-sm" data-action="pause" data-id="${d.id}">${I('pause', 'icon-sm')}Pause</button>` : d.state === 'paused' ? `<button class="btn btn-tonal btn-sm" data-action="resume" data-id="${d.id}">${I('play', 'icon-sm')}Resume</button>` : ''}<button class="btn btn-ghost btn-sm" data-action="cancel" data-id="${d.id}">Cancel</button></div></div>`;
      };
      return `${C.topbar({ title: 'Downloads', sub: 'Resumable · verified before use', back: 'models' })}
      <div class="scroll"><div class="body">
        <div class="banner warn">${I('info')}<span>Downloads in this prototype are simulated and sped up. No data is transferred.</span></div>
        <div class="card"><div class="row between"><span class="eyebrow">Storage</span><span class="small muted tnum">${dev.freeStorageGB.toFixed(1)} GB free</span></div>
          <div class="stack" style="margin-top:12px"><i style="width:${(installed / tot) * 100}%;background:var(--hue-model)"></i><i style="width:${(pending / tot) * 100}%;background:var(--accent);opacity:.6"></i></div>
          <div class="legend" style="margin-top:10px"><span style="--c:var(--hue-model)">Models ${installed.toFixed(1)} GB</span><span style="--c:var(--accent)">Pending ${pending.toFixed(1)} GB</span></div>
          <div class="row between" style="margin-top:14px"><div><b class="small">Wi-Fi only</b><div class="xs faint">Pause on mobile data</div></div>${C.sw('wifi-only', LA.S.settings.wifiOnly, 'wifi', 'Download on Wi-Fi only')}</div></div>
        ${act.length ? C.sectionH('In progress') + act.map(row).join('') : C.empty('download', 'No active downloads', 'Browse the model library to add a model.', '<button class="btn btn-primary" data-go="models">Browse models</button>')}
        ${done.length ? C.sectionH('Completed') + `<div class="list">${done.map((d) => { const m = LA.model(d.modelId); return `<button class="li" data-go="model-${m.id}">${I('check')}<div class="grow"><div class="t">${esc(m.name)}</div><div class="s">Installed ${F.ago(d.finished)} · checksum OK</div></div></button>`; }).join('')}</div>` : ''}
      </div></div>`;
    },
    mount() { this._sig = LA.dlSig(); },
    tick() { const sig = LA.dlSig(); if (sig !== this._sig) { this._sig = sig; LA.refresh(); } else LA.updateDownloads(); },
    changes: { wifi: (el) => { LA.S.settings.wifiOnly = el.checked; LA.save(); } },
    actions: {
      pause: (el) => { const d = LA.S.downloads.find((x) => x.id === el.getAttribute('data-id')); d.state = 'paused'; LA.save(); LA.refresh(); },
      resume: (el) => { const d = LA.S.downloads.find((x) => x.id === el.getAttribute('data-id')); d.state = 'running'; LA.save(); LA.refresh(); },
      cancel: (el) => { const d = LA.S.downloads.find((x) => x.id === el.getAttribute('data-id')); LA.confirm({ title: 'Cancel download?', body: 'The partial file is deleted. You can download again later.', ok: 'Cancel download', danger: true, onOk: () => { d.state = 'cancelled'; LA.model(d.modelId).state = 'available'; LA.S.downloads = LA.S.downloads.filter((x) => x !== d); LA.save(); LA.refresh(); } }); }
    }
  };

  /* ---------- details ---------- */
  let draft = null; let bench = null;
  LA.screens.model = {
    render(id) {
      const m = LA.model(id);
      if (!m) return C.topbar({ title: 'Model not found', back: 'models' }) + '<div class="scroll"><div class="body">' + C.empty('models', 'This model is not in the catalog', 'It may have been deleted.') + '</div></div>';
      if (!draft || draft.id !== id) draft = Object.assign({ id }, LA.profile(m));
      const v = LA.verdict(m, draft.ctx, draft.kv); const e = v.e; const d = LA.device();
      const maxCtx = m.purpose === 'embed' ? 512 : Math.min(m.ctxTrain, 32768);
      const scale = Math.max(v.u, e.total) * 1.05;
      const seg = (val, color, label) => `<i style="width:${(val / scale) * 100}%;background:${color}" title="${label}"></i>`;
      const isActive = m.id === LA.S.activeModel; const dl = dlFor(m);
      const vText = { ok: 'Fits comfortably in this phone’s model budget with room for other apps.', warn: 'Fits, but Android may close background apps. Try a shorter context or q8_0 KV cache.', bad: 'Needs more memory than this phone can safely give a model. Choose a smaller quantisation or model.' }[v.key];
      return `${C.topbar({ title: esc(m.name), sub: esc(m.family) + ' · ' + m.quant, back: 'models', actions: m.state === 'installed' ? C.ib('trash', 'delete', 'Delete model') : '' })}
      <div class="scroll"><div class="body">
        <div class="row wrap" style="gap:6px">${m.tags.map((t) => `<span class="tag model">${esc(t)}</span>`).join('')}${C.sample()}</div>
        <p class="muted" style="margin:0">${esc(m.desc)}</p>
        <div class="stats">${[['Parameters', F.params(m.params)], ['Quantisation', m.quant], ['File size', F.size(m.sizeGB)], ['Architecture', m.arch], ['Trained context', F.ctx(m.ctxTrain)], ['Licence', m.license]].map(([k, x]) => `<div class="stat"><div class="k">${k}</div><div class="v" style="font-size:${String(x).length > 12 ? 12 : 15}px">${esc(x)}</div></div>`).join('')}</div>
        <div class="card"><div class="verdict ${v.key}"><span class="vi">${I(v.icon)}</span><div class="grow"><div class="row between"><b>${v.label} on ${esc(d.label)}</b></div><div class="small muted" style="margin-top:2px">${vText}</div></div></div>
          <div style="margin-top:16px" class="row between small"><span class="muted">Estimated memory at ${F.ctx(draft.ctx)} context</span><b class="tnum">${e.total.toFixed(2)} GB</b></div>
          <div class="stack" style="margin-top:8px;position:relative">${seg(e.weights, 'var(--hue-model)', 'Weights')}${seg(e.kv, 'var(--hue-agent)', 'KV cache')}${seg(e.compute + e.overhead, 'var(--text-3)', 'Compute + runtime')}</div>
          <div class="row between xs faint" style="margin-top:6px"><span>0</span><span>Model budget ${v.u.toFixed(1)} GB</span></div>
          <div class="legend" style="margin-top:8px"><span style="--c:var(--hue-model)">Weights ${e.weights.toFixed(2)}</span><span style="--c:var(--hue-agent)">KV cache ${e.kv.toFixed(2)}</span><span style="--c:var(--text-3)">Compute + runtime ${(e.compute + e.overhead).toFixed(2)}</span></div></div>
        ${m.purpose === 'gen' ? `<div class="card col" style="gap:16px"><div class="row between"><b>Inference settings</b><button class="link" data-action="reset-prof">Reset</button></div>
          <div class="field"><div class="range-row"><label for="r-ctx">Context length</label><output id="o-ctx">${F.num(draft.ctx)}</output></div><input type="range" id="r-ctx" min="9" max="${Math.log2(maxCtx)}" step="1" value="${Math.log2(draft.ctx)}" data-input="ctx" data-change="ctxc" aria-label="Context length"><span class="hint">Longer context remembers more of the conversation but uses more memory and slows the first token.</span></div>
          <div class="field"><div class="range-row"><label for="r-temp">Temperature</label><output id="o-temp">${draft.temp.toFixed(2)}</output></div><input type="range" id="r-temp" min="0" max="1.5" step="0.05" value="${draft.temp}" data-input="temp"></div>
          <div class="field"><div class="range-row"><label for="r-topp">Top-p</label><output id="o-topp">${draft.topP.toFixed(2)}</output></div><input type="range" id="r-topp" min="0.1" max="1" step="0.05" value="${draft.topP}" data-input="topp"></div>
          <div class="field"><span class="label">KV cache precision</span>${C.seg('KV cache precision', [['f16', 'f16'], ['q8_0', 'q8_0'], ['q4_0', 'q4_0']], draft.kv, 'kv', true)}<span class="hint">q8_0 halves KV memory with negligible quality loss. Default on 8–12 GB phones.</span></div>
          <button class="btn btn-tonal" data-action="save-prof">Save settings</button></div>` : `<div class="banner info">${I('info')}<span>Embedding model · ${m.dim} dimensions. Used to index knowledge bases and agent memory. Changing it requires re-indexing.</span></div>`}
        ${m.state === 'installed' && m.purpose === 'gen' ? `<div class="card"><div class="row between"><b>On-device benchmark</b>${C.sample('Simulated')}</div>${bench && bench.id === m.id ? `<div class="stats" style="margin-top:12px">${[['Prompt', bench.p + ' tok/s'], ['Generate', bench.g + ' tok/s'], ['Peak RAM', bench.r + ' GB']].map(([k, x]) => `<div class="stat"><div class="k">${k}</div><div class="v">${x}</div></div>`).join('')}</div>` : `<p class="small muted" style="margin:8px 0 12px">Runs a 128-token prompt and 64-token generation to measure real speed on this phone.</p><button class="btn btn-outline btn-sm" data-action="bench">${I('bolt', 'icon-sm')}Run benchmark</button>`}</div>` : ''}
        <details class="disclosure card"><summary>Chat template<span class="chev">${I('down', 'icon-sm')}</span></summary><pre class="json" style="margin-top:10px">${esc(m.arch.startsWith('qwen') ? '<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{message}<|im_end|>\n<|im_start|>assistant' : m.arch === 'gemma3' ? '<start_of_turn>user\n{system}\n\n{message}<end_of_turn>\n<start_of_turn>model' : m.arch === 'phi3' ? '<|system|>{system}<|end|><|user|>{message}<|end|><|assistant|>' : '<|start_header_id|>system<|end_header_id|>\n{system}<|eot_id|><|start_header_id|>user<|end_header_id|>\n{message}<|eot_id|>')}</pre><p class="xs faint" style="margin:8px 0 0">Read from GGUF metadata at load time.</p></details>
      </div></div>
      <div class="composer" style="flex-direction:row;gap:10px">${m.state === 'installed' ? (m.purpose === 'embed' ? `<button class="btn btn-outline btn-block" data-go="knowledge">Used by knowledge bases</button>` : isActive ? `<button class="btn btn-outline grow" data-action="unload">${LA.modelState() === 'loaded' ? 'Unload' : 'Load'}</button><button class="btn btn-primary grow" data-action="chat">${I('chat', 'icon-sm')}Chat</button>` : `<button class="btn btn-primary btn-block" data-action="activate">Set as active model</button>`) : dl ? `<button class="btn btn-outline btn-block" data-go="dl-progress">View download · ${Math.round(dl.progress * 100)}%</button>` : `<button class="btn btn-primary btn-block" data-action="download">${I('download')}Download ${F.size(m.sizeGB)}</button>`}</div>`;
    },
    unmount() { draft = null; },
    changes: { ctxc: () => LA.refresh() },
    inputs: {
      ctx: (el) => { draft.ctx = Math.pow(2, +el.value); document.getElementById('o-ctx').textContent = F.num(draft.ctx); },
      temp: (el) => { draft.temp = +el.value; document.getElementById('o-temp').textContent = draft.temp.toFixed(2); },
      topp: (el) => { draft.topP = +el.value; document.getElementById('o-topp').textContent = draft.topP.toFixed(2); }
    },
    actions: {
      kv: (el) => { draft.kv = el.getAttribute('data-v'); LA.refresh(); },
      'reset-prof': (el, ev, id) => { delete LA.S.profiles[id]; draft = null; LA.save(); LA.refresh(); },
      'save-prof': (el, ev, id) => { LA.S.profiles[id] = { ctx: draft.ctx, temp: draft.temp, topP: draft.topP, kv: draft.kv }; LA.save(); LA.toast('Inference settings saved'); },
      bench: (el, ev, id) => { el.disabled = true; el.innerHTML = '<span class="spin"></span>Running…'; setTimeout(() => { const d = LA.device(); const m = LA.model(id); const k = 4 / Math.max(1.5, m.params); bench = { id, p: Math.round(d.promptTps * k), g: (d.genTps * k).toFixed(1), r: LA.estimate(m, 2048, 'q8_0').total.toFixed(1) }; LA.refresh(); }, 1800); },
      download: (el, ev, id) => { LA.screens.models.actions.download({ getAttribute: () => id }); },
      activate: (el, ev, id) => { LA.loadModel(id); LA.refresh(); LA.toast('Loading model…'); },
      unload: () => { if (LA.modelState() === 'loaded') { LA.S.modelLoaded = false; LA.toast('Model unloaded'); } else LA.loadModel(LA.S.activeModel); LA.save(); LA.refresh(); },
      chat: () => LA.newChat({}),
      delete: (el, ev, id) => { const m = LA.model(id); LA.confirm({ title: 'Delete ' + esc(m.name) + '?', body: `Frees ${F.size(m.sizeGB)}. Agents assigned to this model will use the active model instead.`, ok: 'Delete', danger: true, onOk: () => { if (m.id === 'import-finetune') LA.S.models = LA.S.models.filter((x) => x !== m); else m.state = 'available'; if (LA.S.activeModel === id) { const other = LA.S.models.find((x) => x.state === 'installed' && x.purpose === 'gen'); LA.S.activeModel = other ? other.id : id; } LA.save(); LA.go('models'); LA.toast('Model deleted'); } }); }
    },
    mount() { this._sig = LA.dlSig() + LA.modelState(); },
    tick() { const sig = LA.dlSig() + LA.modelState(); if (sig !== this._sig) { this._sig = sig; LA.refresh(); } }
  };
})();
