/* Shared helpers, estimators and reusable UI components (string templates). */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  LA.esc = esc;

  /* ---------- formatting ---------- */
  LA.fmt = {
    gb: (x) => (x >= 10 ? x.toFixed(1) : x >= 1 ? x.toFixed(2) : x.toFixed(2)) + ' GB',
    size: (gb) => (gb < 1 ? Math.round(gb * 1024) + ' MB' : gb.toFixed(2) + ' GB'),
    mb: (mb) => (mb < 1 ? Math.max(1, Math.round(mb * 1024)) + ' KB' : mb >= 1024 ? (mb / 1024).toFixed(1) + ' GB' : mb.toFixed(1) + ' MB'),
    params: (b) => (b < 1 ? Math.round(b * 1000) + 'M' : b.toFixed(1).replace('.0', '') + 'B'),
    ctx: (n) => (n >= 1024 ? Math.round(n / 1024) + 'K' : String(n)),
    num: (n) => Number(n).toLocaleString('en-US'),
    ago: (ts) => {
      if (!ts) return 'never';
      const m = Math.round((Date.now() - ts) / 60000);
      if (m < 1) return 'just now';
      if (m < 60) return m + ' min ago';
      const h = Math.round(m / 60);
      if (h < 24) return h + ' h ago';
      const d = Math.round(h / 24);
      return d === 1 ? 'yesterday' : d + ' days ago';
    }
  };

  /* ---------- device + memory model (mirrors MODEL_RUNTIME_DESIGN.md §5–6) ---------- */
  LA.device = () => LA.devices[LA.S.deviceId];
  LA.usableGB = (d) => d.ramGB * 0.5 - 0.4;
  LA.profile = (m) => {
    const p = LA.S.profiles[m.id];
    if (p) return p;
    const d = LA.device();
    const ctx = m.purpose === 'embed' ? 512 : Math.min(m.ctxTrain, d.ramGB >= 12 ? 8192 : 4096);
    return { ctx, temp: 0.7, topP: 0.9, kv: LA.S.settings.kvType };
  };
  LA.estimate = (m, ctx, kv) => {
    const bytesPer = kv === 'f16' ? 2 : kv === 'q4_0' ? 0.5625 : 1.0625;
    const kvGB = (2 * m.layers * ctx * m.kvHeads * m.headDim * bytesPer) / 1073741824;
    const compute = m.purpose === 'embed' ? 0.03 : Math.min(0.45, 0.12 + m.params * 0.025);
    const overhead = m.purpose === 'embed' ? 0.02 : 0.12;
    return { weights: m.sizeGB, kv: kvGB, compute, overhead, total: m.sizeGB + kvGB + compute + overhead };
  };
  LA.verdict = (m, ctx, kv) => {
    const p = LA.profile(m);
    const e = LA.estimate(m, ctx || p.ctx, kv || p.kv);
    const u = LA.usableGB(LA.device());
    if (e.total <= u * 0.85) return { key: 'ok', label: 'Runs well', e, u, icon: 'check' };
    if (e.total <= u) return { key: 'warn', label: 'Tight fit', e, u, icon: 'alert' };
    return { key: 'bad', label: 'Not recommended', e, u, icon: 'close' };
  };
  LA.recRam = (m) => {
    const e = LA.estimate(m, 4096, 'q8_0').total;
    for (const r of [6, 8, 12, 16, 24]) if (e <= (r * 0.5 - 0.4) * 0.85) return r;
    return 24;
  };

  /* ---------- lookups ---------- */
  LA.model = (id) => LA.S.models.find((m) => m.id === id);
  LA.agent = (id) => LA.S.agents.find((a) => a.id === id);
  LA.kb = (id) => LA.S.kbs.find((k) => k.id === id);
  LA.doc = (id) => LA.S.documents.find((d) => d.id === id);
  LA.tool = (id) => LA.S.tools.find((t) => t.id === id);
  LA.conv = (id) => LA.S.conversations.find((c) => c.id === id);
  LA.kbDocs = (id) => LA.S.documents.filter((d) => d.kb === id);
  LA.kbStats = (kb) => {
    const docs = LA.kbDocs(kb.id); const x = LA.S.kbExtra[kb.id] || { docs: 0, chunks: 0, mb: 0 };
    const indexing = docs.filter((d) => ['QUEUED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING'].includes(d.status));
    const failed = docs.filter((d) => d.status === 'FAILED');
    const prog = indexing.length ? indexing.reduce((s, d) => s + d.progress, 0) / indexing.length : 1;
    return {
      docs: docs.length + x.docs, chunks: docs.reduce((s, d) => s + d.chunks, 0) + x.chunks,
      mb: docs.reduce((s, d) => s + d.sizeMB, 0) * 1.35 + x.mb, indexing: indexing.length, failed: failed.length, prog
    };
  };
  LA.uid = (p) => p + '-' + Math.random().toString(36).slice(2, 8);

  /* ---------- colour keys ---------- */
  const HUE = {
    model: ['var(--hue-model-soft)', 'var(--hue-model)'], agent: ['var(--hue-agent-soft)', 'var(--hue-agent)'],
    knowledge: ['var(--hue-knowledge-soft)', 'var(--hue-knowledge)'], accent: ['var(--accent-soft)', 'var(--accent-strong)'],
    warn: ['var(--warn-soft)', 'var(--warn)'], ok: ['var(--ok-soft)', 'var(--ok)'], bad: ['var(--bad-soft)', 'var(--bad)']
  };
  LA.hue = (k) => HUE[k] || HUE.accent;

  /* ---------- components ---------- */
  const C = (LA.C = {});
  C.topbar = (o) => {
    const left = o.back ? `<button class="ibtn" data-action="back" data-to="${esc(o.back)}" aria-label="Back">${I('back')}</button>` : '';
    return `<header class="topbar ${o.bordered ? 'bordered' : ''}">${left}<h1>${o.title}${o.sub ? `<small>${o.sub}</small>` : ''}</h1>${o.actions || ''}</header>`;
  };
  C.ib = (icon, action, label, extra) => `<button class="ibtn" data-action="${action}" aria-label="${esc(label)}" ${extra || ''}>${I(icon)}</button>`;
  C.go = (icon, to, label, badge) => `<button class="ibtn" data-go="${to}" aria-label="${esc(label)}">${I(icon)}${badge ? `<span class="dot">${badge}</span>` : ''}</button>`;
  C.sample = (t) => `<span class="sample">${t || 'Sample data'}</span>`;
  const STATUS = {
    completed: ['ok', 'Completed'], failed: ['bad', 'Failed'], running: ['run', 'Running'], pending: ['pending', 'Pending'],
    never: ['pending', 'Not run yet'], skipped: ['skip', 'Skipped'], cancelled: ['warn', 'Cancelled'],
    INDEXED: ['ok', 'Indexed'], FAILED: ['bad', 'Failed'], QUEUED: ['pending', 'Queued'], EXTRACTING: ['run', 'Extracting'],
    CHUNKING: ['run', 'Chunking'], EMBEDDING: ['run', 'Embedding'], NEEDS_REINDEX: ['warn', 'Needs re-index']
  };
  C.status = (s, label) => { const v = STATUS[s] || ['pending', s]; return `<span class="status ${v[0]}">${esc(label || v[1])}</span>`; };
  C.avatar = (a, size) => { const h = LA.hue(a.color); return `<span class="avatar ${size || ''}" style="background:${h[0]};color:${h[1]}">${I(a.icon || 'agents')}</span>`; };
  C.kbIcon = (kb, size) => { const h = LA.hue(kb.color); return `<span class="avatar ${size || ''}" style="background:${h[0]};color:${h[1]}">${I('knowledge')}</span>`; };
  C.progress = (v, cls) => `<div class="progress ${cls || ''}"><i style="width:${Math.round(Math.max(0, Math.min(1, v)) * 100)}%"></i></div>`;
  C.sw = (id, checked, action, label, disabled) => `<label class="switch"><input type="checkbox" id="${id}" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''} data-change="${action}" aria-label="${esc(label)}"><i></i></label>`;
  C.seg = (name, opts, value, action, full) => `<div class="seg ${full ? 'full' : ''}" role="radiogroup" aria-label="${esc(name)}">${opts.map((o) => `<button role="radio" aria-checked="${o[0] === value}" class="${o[0] === value ? 'on' : ''}" data-action="${action}" data-v="${esc(o[0])}">${esc(o[1])}</button>`).join('')}</div>`;
  C.verdictPill = (v) => `<span class="status ${v.key}">${v.label}</span>`;
  C.sectionH = (title, link, linkTo) => `<div class="section-h"><h2>${title}</h2>${link ? `<button class="link" data-go="${linkTo}">${link}</button>` : ''}</div>`;
  C.modelTag = (id) => { const m = id && LA.model(id); return `<span class="tag model">${I('models', 'icon-sm')}${m ? esc(m.name.replace(' Instruct', '')) : 'Active model'}</span>`; };
  C.empty = (icon, title, sub, btn) => `<div class="empty">${I(icon)}<b>${title}</b><span class="small">${sub}</span>${btn || ''}</div>`;

  LA.updateDownloads = function () {
    LA.S.downloads.forEach((d) => {
      const m = LA.model(d.modelId);
      document.querySelectorAll(`[data-dlp="${d.id}"] .progress > i`).forEach((i) => (i.style.width = Math.round(d.progress * 100) + '%'));
      document.querySelectorAll(`[data-dlt="${d.id}"]`).forEach((t) => (t.textContent = d.state === 'paused' ? 'Paused' : d.state === 'verifying' ? 'Verifying' : Math.round(d.progress * 100) + '%'));
      document.querySelectorAll(`[data-dlx="${d.id}"]`).forEach((t) => (t.textContent = (m.sizeGB * d.progress).toFixed(2) + ' of ' + m.sizeGB.toFixed(2) + ' GB'));
      document.querySelectorAll(`[data-dls="${d.id}"]`).forEach((t) => (t.textContent = d.state === 'running' ? d.speedMBs.toFixed(1) + ' MB/s · ~' + Math.ceil((m.sizeGB * (1 - d.progress) * 1024) / d.speedMBs / 9) + ' s left (demo speed)' : ''));
    });
  };
  // A cheap signature of download states: a full refresh is needed only when it changes.
  LA.dlSig = () => LA.S.downloads.map((d) => d.id + d.state).join('|') + LA.S.models.map((m) => m.state[0]).join('');

  /* ---------- sheets & toasts ---------- */
  LA.sheet = function (o) {
    LA.closeSheet();
    const view = document.querySelector('.device');
    const wrap = document.createElement('div');
    wrap.className = 'sheet-layer';
    wrap.innerHTML = `<div class="scrim" data-action="close-sheet"></div><div class="sheet" role="dialog" aria-modal="true" aria-label="${esc(o.title)}"><div class="grab"></div><div class="sh-head"><h3>${o.title}</h3>${C.ib('close', 'close-sheet', 'Close')}</div><div class="sh-body">${o.body}</div>${o.actions ? `<div class="sh-actions">${o.actions}</div>` : ''}</div>`;
    view.appendChild(wrap);
    LA.sheetActions = o.handlers || {};
    if (o.mount) o.mount(wrap);
    const f = wrap.querySelector('.sheet input, .sheet textarea, .sheet button:not([data-action=close-sheet])');
    if (f && o.focus !== false) setTimeout(() => f.focus({ preventScroll: true }), 50);
    return wrap;
  };
  LA.closeSheet = function () { document.querySelectorAll('.sheet-layer').forEach((n) => n.remove()); LA.sheetActions = {}; };
  LA.confirm = function (o) {
    LA.sheet({
      title: o.title, body: `<p class="muted" style="margin:0">${o.body}</p>`,
      actions: `<button class="btn btn-ghost" data-action="close-sheet">Cancel</button><button class="btn ${o.danger ? 'btn-danger' : 'btn-primary'}" data-action="sheet-ok">${o.ok}</button>`,
      handlers: { 'sheet-ok': () => { LA.closeSheet(); o.onOk(); } }
    });
  };
  let toastTimer;
  LA.toast = function (msg) {
    document.querySelectorAll('.toast').forEach((n) => n.remove());
    const t = document.createElement('div');
    t.className = 'toast'; t.setAttribute('role', 'status'); t.innerHTML = msg;
    document.querySelector('.device').appendChild(t);
    clearTimeout(toastTimer); toastTimer = setTimeout(() => t.remove(), 2600);
  };
  LA.copy = function (text) {
    try {
      navigator.clipboard.writeText(text).then(() => LA.toast('Copied to clipboard'), () => LA.toast('Copy is blocked here. Long-press the text to select it.'));
    } catch (e) { LA.toast('Copy is blocked here. Long-press the text to select it.'); }
  };

  /* ---------- minimal markdown (escape first, then format) ---------- */
  const highlight = (code, lang) => {
    let h = esc(code);
    if (!/kotlin|kt|java|json|ts|js/.test(lang || '')) return h;
    h = h.replace(/(&quot;.*?&quot;)/g, '<span class="str">$1</span>')
      .replace(/(\/\/[^\n]*)/g, '<span class="cm">$1</span>')
      .replace(/\b(val|var|fun|suspend|class|interface|data|sealed|object|return|override|private|import|package|when|if|else|null|true|false)\b/g, '<span class="kw">$1</span>')
      .replace(/\b([A-Z][A-Za-z0-9]+)\b/g, '<span class="ty">$1</span>');
    return h;
  };
  const inline = (s) => esc(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\[chunk:(\d+)\]/g, '<button class="cite" data-action="cite" data-n="$1" aria-label="Source $1">$1</button>');
  LA.md = function (text) {
    const out = []; const lines = String(text).split('\n'); let i = 0; let list = null;
    const closeList = () => { if (list) { out.push(list.type === 'ul' ? '</ul>' : '</ol>'); list = null; } };
    while (i < lines.length) {
      const ln = lines[i];
      const fence = ln.match(/^```(\w*)/);
      if (fence) {
        closeList(); const lang = fence[1]; const buf = []; i++;
        while (i < lines.length && !lines[i].startsWith('```')) { buf.push(lines[i]); i++; }
        i++;
        out.push(`<div class="codeblock"><div class="cb-head"><span>${esc(lang || 'text')}</span><button class="ibtn" style="width:30px;height:30px" data-action="copy-code" aria-label="Copy code">${I('copy', 'icon-sm')}</button></div><pre><code>${highlight(buf.join('\n'), lang)}</code></pre></div>`);
        continue;
      }
      let m;
      if ((m = ln.match(/^#{1,4}\s+(.*)/))) { closeList(); out.push(`<h3>${inline(m[1])}</h3>`); }
      else if ((m = ln.match(/^\s*[-*]\s+(.*)/))) { if (!list || list.type !== 'ul') { closeList(); list = { type: 'ul' }; out.push('<ul>'); } out.push(`<li>${inline(m[1])}</li>`); }
      else if ((m = ln.match(/^\s*\d+\.\s+(.*)/))) { if (!list || list.type !== 'ol') { closeList(); list = { type: 'ol' }; out.push('<ol>'); } out.push(`<li>${inline(m[1])}</li>`); }
      else if (!ln.trim()) { closeList(); }
      else { closeList(); out.push(`<p>${inline(ln)}</p>`); }
      i++;
    }
    closeList();
    return out.join('');
  };

  /* ---------- safe calculator (recursive descent; never eval) ---------- */
  LA.calc = function (src) {
    const s = String(src).replace(/×/g, '*').replace(/÷/g, '/').replace(/\s+/g, '');
    if (s.length > 200) throw new Error('Expression is longer than 200 characters');
    let p = 0;
    const peek = () => s[p];
    const num = () => {
      const m = s.slice(p).match(/^(\d+(\.\d+)?|\.\d+)/);
      if (!m) throw new Error('Expected a number at position ' + (p + 1));
      p += m[0].length; return parseFloat(m[0]);
    };
    const fns = { sqrt: Math.sqrt, log: Math.log10, ln: Math.log, sin: Math.sin, cos: Math.cos, tan: Math.tan, abs: Math.abs };
    const atom = () => {
      if (peek() === '(') { p++; const v = expr(); if (peek() !== ')') throw new Error('Missing closing parenthesis'); p++; return v; }
      if (peek() === '-') { p++; return -atom(); }
      const f = s.slice(p).match(/^[a-z]+/);
      if (f) { if (!fns[f[0]]) throw new Error('Unknown function “' + f[0] + '”'); p += f[0].length; if (peek() !== '(') throw new Error('Expected ( after ' + f[0]); return fns[f[0]](atom()); }
      if (s.slice(p, p + 2) === 'pi') { p += 2; return Math.PI; }
      return num();
    };
    const power = () => { let v = atom(); while (peek() === '^') { p++; v = Math.pow(v, atom()); } if (peek() === '%') { p++; v = v / 100; } return v; };
    const term = () => { let v = power(); while (peek() === '*' || peek() === '/') { const o = s[p++]; const r = power(); if (o === '/' && r === 0) throw new Error('Division by zero'); v = o === '*' ? v * r : v / r; } return v; };
    const expr = () => { let v = term(); while (peek() === '+' || peek() === '-') { const o = s[p++]; const r = term(); v = o === '+' ? v + r : v - r; } return v; };
    if (!s) throw new Error('Enter an expression');
    const v = expr();
    if (p !== s.length) throw new Error('Unexpected “' + s[p] + '” at position ' + (p + 1));
    return v;
  };

  /* ---------- sparkline ---------- */
  LA.spark = function (vals, max, label) {
    const W = 320, H = 64, n = vals.length; if (!n) return '';
    const mx = max || Math.max(...vals) * 1.2 || 1;
    const pts = vals.map((v, i) => [(i / (Math.max(1, n - 1))) * (W - 8) + 4, H - 6 - (v / mx) * (H - 14)]);
    const line = pts.map((p, i) => (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
    const last = pts[pts.length - 1];
    return `<svg class="spark" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="${esc(label || 'trend')}">
      <line class="grid" x1="0" x2="${W}" y1="${H - 6}" y2="${H - 6}"/><line class="grid" x1="0" x2="${W}" y1="${(H - 6) / 2}" y2="${(H - 6) / 2}" stroke-dasharray="3 4"/>
      <path class="area" d="${line} L${last[0].toFixed(1)} ${H - 6} L4 ${H - 6} Z"/><path class="line" d="${line}"/>
      <circle class="end" cx="${last[0].toFixed(1)}" cy="${last[1].toFixed(1)}" r="3"/></svg>`;
  };
})();
