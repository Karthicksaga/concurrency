/* Welcome / onboarding and Home dashboard. */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc; const F = LA.fmt;
  LA.screens = LA.screens || {}; LA.tickers = LA.tickers || [];

  /* ---------- model load simulation ---------- */
  LA.loadModel = function (id) {
    LA.S.activeModel = id; LA.S.modelLoaded = false; LA.S.loadingUntil = Date.now() + 2200; LA.save();
  };
  LA.tickers.push(() => {
    if (!LA.S.modelLoaded && LA.S.loadingUntil && Date.now() > LA.S.loadingUntil) {
      LA.S.modelLoaded = true; LA.S.loadingUntil = null;
      const m = LA.model(LA.S.activeModel); LA.toast(esc(m.name) + ' loaded (simulated)'); return true;
    }
    return false;
  });
  LA.modelState = () => (LA.S.modelLoaded ? 'loaded' : LA.S.loadingUntil ? 'loading' : 'unloaded');

  /* ---------- onboarding ---------- */
  let step = 0; let scanDone = false; let pick = null;
  const recommended = () => {
    const d = LA.device();
    return LA.S.models.filter((m) => m.purpose === 'gen').map((m) => ({ m, v: LA.verdict(m) })).filter((x) => x.v.key === 'ok').sort((a, b) => b.m.params - a.m.params).slice(0, 3);
  };
  const art = {
    0: `<div class="chipgrid">${Array.from({ length: 36 }, (_, i) => `<i style="animation-delay:${(i % 7) * 0.35}s"></i>`).join('')}</div><div class="core"><b>LOCAL<span>·</span>AI</b></div>`,
    1: `<div class="col" style="align-items:center;gap:14px">${I('shield', 'icon')}<div class="row" style="gap:8px">${['Chats', 'Documents', 'Memory', 'Agents'].map((t) => `<span class="tag">${I('lock', 'icon-sm')}${t}</span>`).join('')}</div><span class="small faint">Stored only on this device</span></div>`
  };
  LA.screens.welcome = {
    render() {
      const d = LA.device();
      const dots = `<div class="dots" aria-label="Step ${step + 1} of 4">${[0, 1, 2, 3].map((i) => `<i class="${i === step ? 'on' : ''}"></i>`).join('')}</div>`;
      let body = '';
      if (step === 0) body = `<div class="art">${art[0]}</div><div class="col" style="gap:12px"><span class="eyebrow">Personal AI, on device</span><h2>Your models, agents and documents. On your phone.</h2><p class="lead">Run open-source language models locally, build agents that use your own knowledge, and keep everything offline.</p></div>`;
      if (step === 1) body = `<div class="art" style="color:var(--ok)">${art[1]}</div><div class="col" style="gap:12px"><span class="eyebrow">Privacy by design</span><h2>Nothing leaves this device.</h2><p class="lead">No account and no analytics. The internet is used only when you download a model, and every connection is listed in Settings.</p></div>
        <div class="list">${[['wifi_off', 'Works offline', 'Chat, search and agents run without a connection.'], ['shield', 'You control memory', 'Agents ask before they remember anything.'], ['tool', 'Safe tools only', 'Agents use built-in tools with permissions. No code execution.']].map(([ic, t, s]) => `<div class="li"><span class="avatar sm" style="background:var(--surface-3);color:var(--text-2)">${I(ic)}</span><div class="grow"><div class="t">${t}</div><div class="s">${s}</div></div></div>`).join('')}</div>`;
      if (step === 2) body = `<div class="col" style="gap:12px"><span class="eyebrow">Step 3 · Device check</span><h2>Checking what this phone can run</h2><p class="lead">We read memory, processor and storage to recommend models that will run smoothly.</p></div>
        <div class="card"><div class="row between" style="margin-bottom:6px"><b>${esc(d.label)}</b>${C.sample('Simulated')}</div>
        ${[['Memory', d.ramGB + ' GB total · ' + d.availGB + ' GB free'], ['Processor', d.soc], ['CPU features', d.features], ['GPU', d.gpu + ' (GPU acceleration is experimental)'], ['Free storage', d.freeStorageGB + ' GB'], ['Model budget', '≈ ' + LA.usableGB(d).toFixed(1) + ' GB for a model']].map(([k, v], i) => `<div class="check-row"><span class="k">${k}</span>${scanDone || i < 2 ? `<span class="v">${esc(v)}</span>` : '<span class="spin" aria-label="Checking"></span>'}</div>`).join('')}</div>
        ${scanDone ? `<div class="banner ok">${I('check')}<span>This phone runs <b>3–4B</b> models well${d.ramGB >= 12 ? ' and <b>7–8B</b> models with care' : ''}.</span></div>` : ''}`;
      if (step === 3) {
        const recs = recommended(); if (!pick && recs[0]) pick = recs[0].m.id;
        body = `<div class="col" style="gap:12px"><span class="eyebrow">Step 4 · First model</span><h2>Pick a starter model</h2><p class="lead">Recommended for your ${d.ramGB} GB phone. You can add more any time.</p></div>
        <div class="col">${recs.map(({ m, v }) => `<label class="check ${pick === m.id ? 'on' : ''}"><input type="radio" name="starter" value="${m.id}" ${pick === m.id ? 'checked' : ''} data-change="pick-starter"><div class="grow"><div class="row between"><b>${esc(m.name)}</b>${C.verdictPill(v)}</div><div class="small muted">${F.params(m.params)} · ${m.quant} · ${F.size(m.sizeGB)} download · ${esc(m.license)}</div></div></label>`).join('')}</div>
        <p class="small faint" style="margin:0">Download needs Wi-Fi by default. Model files come from Hugging Face; checksums are verified before use.</p>`;
      }
      const primary = step === 3 ? `<button class="btn btn-primary btn-block" data-action="onb-finish">${I('download')}Download and continue</button>` : step === 2 && !scanDone ? `<button class="btn btn-primary btn-block" disabled>Checking…</button>` : `<button class="btn btn-primary btn-block" data-action="onb-next">Continue</button>`;
      return `<div class="onb">${body}</div><div style="padding:0 var(--gutter-r) 20px var(--gutter-l);display:flex;flex-direction:column;gap:14px"><div class="row between">${dots}<button class="btn btn-ghost btn-sm" data-action="onb-skip">Skip</button></div>${primary}</div>`;
    },
    mount() {
      if (step === 2 && !scanDone) setTimeout(() => { scanDone = true; if (LA.cur().key === 'welcome') LA.refresh(); }, 1600);
    },
    actions: {
      'onb-next': () => { step = Math.min(3, step + 1); LA.refresh(); },
      'onb-skip': () => { LA.S.onboarded = true; LA.save(); step = 0; LA.go('home'); },
      'onb-finish': () => {
        LA.S.onboarded = true;
        const m = LA.model(pick);
        const existing = m && LA.S.downloads.find((x) => x.modelId === m.id && ['paused', 'running'].includes(x.state));
        if (existing) { existing.state = 'running'; m.state = 'downloading'; }
        else if (m && m.state === 'available') { m.state = 'downloading'; LA.S.downloads.unshift({ id: LA.uid('dl'), modelId: m.id, progress: 0, speedMBs: 16, state: 'running' }); }
        LA.save(); step = 0; LA.go('home'); LA.toast(m && m.state === 'downloading' ? 'Download started (simulated)' : 'Ready');
      }
    },
    changes: { 'pick-starter': (el) => { pick = el.value; LA.refresh(); } }
  };

  /* ---------- home ---------- */
  function ramCard() {
    const d = LA.device(); const m = LA.model(LA.S.activeModel); const st = LA.modelState();
    const est = st === 'loaded' ? LA.estimate(m, LA.profile(m).ctx, LA.profile(m).kv) : null;
    const model = est ? est.total : 0;
    // Android reclaims cached apps when a model loads; figures are illustrative.
    const system = d.ramGB - d.availGB - Math.min(1.0, model * 0.3);
    const free = Math.max(0.2, d.ramGB - system - model);
    const tot = system + model + free;
    return `<div class="card"><div class="row between" style="margin-bottom:12px"><div><div class="eyebrow">Device memory</div><div class="big-num" style="margin-top:6px">${free.toFixed(1)}<small>GB free of ${d.ramGB} GB</small></div></div>${C.go('monitor', 'monitor', 'Performance monitor')}</div>
      <div class="stack" role="img" aria-label="Memory usage"><i style="width:${(system / tot) * 100}%;background:var(--text-3)"></i><i style="width:${(model / tot) * 100}%;background:var(--accent)"></i><i style="width:${(free / tot) * 100}%;background:var(--ok);opacity:.55"></i></div>
      <div class="legend" style="margin-top:10px"><span style="--c:var(--text-3)">Android &amp; apps ${system.toFixed(1)} GB</span><span style="--c:var(--accent)">Model ${model.toFixed(1)} GB</span><span style="--c:var(--ok)">Free</span></div>
      <div class="row wrap" style="margin-top:12px;gap:8px"><span class="tag">${I('thermo', 'icon-sm')}Thermal: normal</span><span class="tag">${I('battery', 'icon-sm')}78% · not charging</span><span class="tag">${I('wifi_off', 'icon-sm')}Offline ready</span></div></div>`;
  }
  function activeModelCard() {
    const m = LA.model(LA.S.activeModel); const st = LA.modelState(); const p = LA.profile(m); const d = LA.device();
    const pill = st === 'loaded' ? C.status('completed', 'Loaded') : st === 'loading' ? C.status('running', 'Loading…') : C.status('pending', 'Not loaded');
    return `<div class="card hero"><div class="row between"><span class="eyebrow">Active model</span>${pill}</div>
      <div class="row" style="margin-top:12px;gap:12px"><span class="avatar" style="background:var(--hue-model-soft);color:var(--hue-model)">${I('models')}</span><div class="grow"><div style="font:600 17px/1.25 var(--font-display)">${esc(m.name)}</div><div class="small muted">${F.params(m.params)} · ${m.quant} · ${F.ctx(p.ctx)} context · CPU ${d.perfCores} threads</div></div></div>
      ${st === 'loading' ? `<div style="margin-top:12px">${C.progress(1 - Math.max(0, (LA.S.loadingUntil - Date.now()) / 2200))}</div>` : ''}
      <div class="row" style="margin-top:14px;gap:8px"><button class="btn btn-tonal btn-sm" data-action="pick-model">${I('models', 'icon-sm')}Change</button>${st === 'loaded' ? `<button class="btn btn-ghost btn-sm" data-action="unload">Unload</button>` : st === 'unloaded' ? `<button class="btn btn-ghost btn-sm" data-action="load">Load</button>` : ''}<span class="grow"></span><button class="link" data-go="model-${m.id}">Details</button></div></div>`;
  }
  LA.modelPicker = function (onPick) {
    const items = LA.S.models.filter((m) => m.purpose === 'gen' && m.state === 'installed');
    LA.sheet({
      title: 'Choose a model',
      body: `<p class="small muted" style="margin:0">Only one model is loaded at a time. Switching unloads the current model.</p><div class="list">${items.map((m) => { const v = LA.verdict(m); return `<button class="li" data-action="sheet-pick" data-id="${m.id}"><span class="avatar sm" style="background:var(--hue-model-soft);color:var(--hue-model)">${I('models')}</span><div class="grow"><div class="t">${esc(m.name)}</div><div class="s">${m.quant} · ${F.size(m.sizeGB)} · est. ${v.e.total.toFixed(1)} GB in memory</div></div>${m.id === LA.S.activeModel ? I('check') : C.verdictPill(v)}</button>`; }).join('')}</div><button class="btn btn-outline" data-go="models">${I('download')}Get more models</button>`,
      handlers: { 'sheet-pick': (el) => { const id = el.getAttribute('data-id'); LA.closeSheet(); if (id !== LA.S.activeModel || !LA.S.modelLoaded) LA.loadModel(id); if (onPick) onPick(id); LA.refresh(); } }
    });
  };
  LA.screens.home = {
    nav: 'home',
    render() {
      const S = LA.S; const dls = S.downloads.filter((d) => d.state === 'running' || d.state === 'paused');
      const hr = new Date().getHours(); const greet = hr < 12 ? 'Good morning' : hr < 18 ? 'Good afternoon' : 'Good evening';
      const convs = S.conversations.slice().sort((a, b) => b.updated - a.updated).slice(0, 3);
      const agents = S.agents.slice().sort((a, b) => (b.last.at || 0) - (a.last.at || 0)).slice(0, 5);
      return `${C.topbar({ title: greet, sub: 'Everything below runs on this device', actions: C.go('monitor', 'monitor', 'Performance monitor') + C.go('settings', 'prefs', 'Settings') })}
      <div class="scroll"><div class="body">
        ${activeModelCard()}
        <div class="qa">${[['chat', 'New chat', 'new-chat', 'accent'], ['wave', 'Talk', 'talk', 'agent'], ['camera', 'Ask a photo', 'ask-photo', 'model'], ['doc', 'Documents', 'go-studio', 'knowledge'], ['knowledge', 'Ask docs', 'ask-docs', 'knowledge'], ['workflow', 'Workflow', 'go-workflow', 'agent'], ['upload', 'Import model', 'import-model', 'model'], ['models', 'Models', 'go-models', 'accent']].map(([ic, t, a, h]) => { const c = LA.hue(h); return `<button data-action="${a}"><span class="ic" style="background:${c[0]};color:${c[1]}">${I(ic)}</span>${t}</button>`; }).join('')}</div>
        ${ramCard()}
        ${dls.length ? `<div class="card tight tap" data-go="downloads" role="link"><div class="row between"><b class="small">${dls.length} download${dls.length > 1 ? 's' : ''}</b><span class="small faint">View</span></div>${dls.map((d) => { const m = LA.model(d.modelId); return `<div style="margin-top:10px"><div class="row between small"><span class="ellipsis">${esc(m.name)}</span><span class="faint tnum" data-dlt="${d.id}">${d.state === 'paused' ? 'Paused' : Math.round(d.progress * 100) + '%'}</span></div><div style="margin-top:6px" data-dlp="${d.id}">${C.progress(d.progress, d.state === 'paused' ? 'warn' : '')}</div></div>`; }).join('')}</div>` : ''}
        ${C.sectionH('Recent conversations', 'All', 'chat')}
        <div class="list">${convs.map((c) => LA.convRow(c)).join('')}</div>
        ${C.sectionH('Agents', 'Agent Studio', 'agents')}
        <div class="hscroll">${agents.map((a) => `<button class="card tap mini-agent" data-go="agent-${a.id}">${C.avatar(a, 'sm')}<div><div class="t" style="font-weight:600">${esc(a.name)}</div><div class="small muted ellipsis">${esc(a.role)}</div></div>${C.status(a.last.status === 'never' ? 'never' : a.last.status)}</button>`).join('')}<button class="card tap mini-agent" data-go="agent-new" style="justify-content:center;align-items:center;color:var(--text-2)">${I('plus')}<span class="small">New agent</span></button></div>
        ${C.sectionH('Knowledge bases', 'All', 'knowledge')}
        <div class="list">${S.kbs.slice(0, 3).map((k) => LA.kbRow(k)).join('')}</div>
        <p class="small faint" style="text-align:center;margin:4px 0 0">Prototype · all figures are sample data</p>
      </div></div>`;
    },
    tick() {
      const sig = LA.dlSig() + LA.modelState();
      if (sig !== this._sig || LA.modelState() === 'loading') { const first = this._sig === undefined; this._sig = sig; if (!first) LA.refresh(); return; }
      LA.updateDownloads();
    },
    mount() { this._sig = LA.dlSig() + LA.modelState(); },
    actions: {
      'pick-model': () => LA.modelPicker(),
      unload: () => { LA.S.modelLoaded = false; LA.S.loadingUntil = null; LA.save(); LA.refresh(); LA.toast('Model unloaded. Memory released.'); },
      load: () => { LA.loadModel(LA.S.activeModel); LA.refresh(); },
      'new-chat': () => LA.newChat({}),
      'ask-docs': () => LA.newChat({ kbs: ['kb-finance'] }),
      'go-workflow': () => LA.go('workflow'),
      talk: () => LA.go('voice'),
      'go-studio': () => LA.go('studio'),
      'go-models': () => LA.go('models'),
      'ask-photo': () => {
        LA.photoSheet((p) => {
          const mid = LA.isVision(LA.S.activeModel) ? LA.S.activeModel : 'smolvlm-500m';
          LA.newChat({}); const c = LA.S.conversations[0]; c.model = mid; c.pendingPhoto = p;
          if (mid !== LA.S.activeModel) { LA.loadModel(mid); LA.toast('Switched to SmolVLM 500M (vision)'); }
          LA.save(); LA.refresh();
        });
      },
      'import-model': () => LA.importModelSheet()
    }
  };

  LA.convRow = (c) => {
    const ag = c.agent === 'supervisor' ? { name: 'Supervisor workflow' } : c.agent ? LA.agent(c.agent) : null;
    const last = c.messages[c.messages.length - 1];
    const preview = last ? last.text.replace(/[*`#\[\]]/g, '').replace(/chunk:\d+/g, '').slice(0, 80) : 'No messages yet';
    return `<button class="li" data-go="chat-${c.id}"><span class="avatar sm" style="background:var(--surface-3);color:var(--text-2)">${I(c.agentMode ? 'workflow' : 'chat')}</span><div class="grow"><div class="row between"><span class="t ellipsis">${esc(c.title)}</span><span class="xs faint" style="flex:none">${F.ago(c.updated)}</span></div><div class="s ellipsis">${ag ? esc(ag.name) + ' · ' : ''}${esc(preview)}</div></div></button>`;
  };
  LA.kbRow = (k) => {
    const s = LA.kbStats(k);
    const st = s.indexing ? C.status('EMBEDDING', 'Indexing ' + Math.round(s.prog * 100) + '%') : s.failed ? C.status('FAILED', s.failed + ' error') : C.status('INDEXED');
    return `<button class="li" data-go="kb-${k.id}">${C.kbIcon(k, 'sm')}<div class="grow"><div class="t ellipsis">${esc(k.name)}</div><div class="s">${s.docs} docs · ${F.mb(s.mb)} · ${F.ago(k.updated)}</div></div>${st}</button>`;
  };
})();
