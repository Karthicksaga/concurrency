/* Knowledge Studio: dashboard, create KB, KB detail, document detail, simulated indexing + search. */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc; const F = LA.fmt;
  LA.screens = LA.screens || {}; LA.tickers = LA.tickers || [];
  const ACTIVE = ['QUEUED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING'];

  /* ---------- indexing simulation: extract → chunk → embed → indexed ---------- */
  LA.tickers.push(() => {
    if (LA.S.settings.indexOnCharge) return false;
    let ch = false;
    LA.S.kbs.forEach((kb) => {
      const q = LA.kbDocs(kb.id).filter((d) => ACTIVE.includes(d.status));
      q.slice(0, 2).forEach((d) => { // two documents at a time per KB
        d.progress = Math.min(1, d.progress + 0.035 / Math.max(0.6, Math.sqrt(d.sizeMB)));
        d.status = d.progress < 0.15 ? 'EXTRACTING' : d.progress < 0.3 ? 'CHUNKING' : d.progress < 1 ? 'EMBEDDING' : 'INDEXED';
        if (d.status === 'INDEXED') { d.chunks = d.chunks || Math.max(2, Math.round((d.pages || 2) * 2.6)); d.indexedAt = Date.now(); kb.updated = Date.now(); }
        ch = true;
      });
    });
    return ch;
  });

  /* ---------- mock semantic search ---------- */
  const hash = (s) => { let h = 0; for (const c of s) h = (h * 31 + c.charCodeAt(0)) | 0; return Math.abs(h); };
  LA.search = function (query, kbIds) {
    const words = query.toLowerCase().split(/\W+/).filter((w) => w.length > 2);
    const out = [];
    LA.S.documents.filter((d) => (!kbIds || kbIds.includes(d.kb)) && d.status === 'INDEXED').forEach((d) => {
      d.excerpt.forEach((p, i) => {
        const t = (d.title + ' ' + p).toLowerCase();
        const hits = words.filter((w) => t.includes(w)).length;
        const score = Math.min(0.95, 0.38 + hits * 0.14 + (hash(d.id + i + query) % 100) / 1000);
        out.push({ d, i, p, score, hits });
      });
    });
    return out.sort((a, b) => b.score - a.score).slice(0, 5);
  };
  function results(query, kbIds) {
    if (!query.trim()) return '';
    const r = LA.search(query, kbIds);
    if (!r.length) return C.empty('search', 'No indexed passages yet', 'Wait for indexing to finish, then search again.');
    return `<div class="row between"><span class="small faint">Top ${r.length} passages · hybrid vector + keyword</span>${C.sample('Mock ranking')}</div>` + r.map((x, n) => `<button class="card tap tight" data-go="doc-${x.d.id}"><div class="row between"><span class="small" style="font-weight:600">${esc(x.d.title)}</span><span class="mono" style="color:var(--hue-knowledge)">${x.score.toFixed(2)}</span></div><div class="chunk" style="margin-top:8px"><p>${esc(x.p.length > 180 ? x.p.slice(0, 178) + '…' : x.p)}</p></div><div class="xs faint" style="margin-top:6px">${esc(LA.kb(x.d.kb).name)} · ${x.d.pages ? 'p. ' + (2 + x.i * 2) : 'section ' + (x.i + 1)} · chunk ${100 + hash(x.d.id) % 300 + x.i}</div></button>`).join('');
  }

  /* ---------- dashboard ---------- */
  let dq = '';
  LA.screens.knowledge = {
    nav: 'knowledge',
    render() {
      const st = LA.S.kbs.map((k) => LA.kbStats(k));
      const docs = st.reduce((s, x) => s + x.docs, 0); const mb = st.reduce((s, x) => s + x.mb, 0); const chunks = st.reduce((s, x) => s + x.chunks, 0);
      const em = LA.model('me5-small');
      return `${C.topbar({ title: 'Knowledge', sub: 'Local RAG · ' + esc(em.name), actions: C.ib('info', 'how', 'How retrieval works') })}
      <div class="scroll"><div class="body">
        <div class="stats">${[['Documents', F.num(docs)], ['Chunks', F.num(chunks)], ['On disk', F.mb(mb)]].map(([k, v]) => `<div class="stat"><div class="k">${k}</div><div class="v">${v}</div></div>`).join('')}</div>
        <div class="search">${I('search')}<input id="kb-q" placeholder="Search all knowledge semantically" value="${esc(dq)}" data-input="q" aria-label="Semantic search"></div>
        <div class="col" id="kb-results" style="gap:10px">${results(dq)}</div>
        ${C.sectionH('Knowledge bases')}
        <div class="col" style="gap:12px">${LA.S.kbs.map((k) => kbCard(k)).join('')}</div>
        <div style="height:64px"></div></div></div>
      <button class="btn btn-primary fab" data-go="kb-new">${I('plus')}New knowledge base</button>`;
    },
    tick() {
      const sig = LA.S.documents.map((d) => d.status[0]).join('');
      if (sig !== this._sig) { const first = this._sig === undefined; this._sig = sig; if (!first) { LA.refresh(); return; } }
      updateBars();
    },
    mount() { this._sig = LA.S.documents.map((d) => d.status[0]).join(''); },
    inputs: { q: (el) => { dq = el.value; clearTimeout(LA._sq); LA._sq = setTimeout(() => { const r = document.getElementById('kb-results'); if (r) r.innerHTML = results(dq); }, 180); } },
    actions: { how: () => LA.sheet({ title: 'How local retrieval works', body: `<div class="list">${[['file', 'Extract', 'Text is read from PDF, Word, Markdown, text and code in an isolated process.'], ['grid', 'Chunk', '~400-token passages that keep headings and page numbers.'], ['sparkle', 'Embed', 'A small on-device model turns each passage into 384 numbers.'], ['database', 'Store', 'Vectors and a keyword index live in a local SQLite database.'], ['search', 'Retrieve', 'Your question is matched by meaning and by keywords; the best passages are cited in the answer.']].map(([i, t, s]) => `<div class="li" style="align-items:flex-start">${I(i)}<div class="grow"><div class="t">${t}</div><div class="s">${s}</div></div></div>`).join('')}</div>` }) }
  };
  function updateBars() { LA.S.kbs.forEach((k) => { const el = document.querySelector(`[data-kbp="${k.id}"]`); if (el) el.outerHTML = kbStatus(k); const b = document.querySelector(`[data-kbbar="${k.id}"] .progress > i`); if (b) b.style.width = Math.round(LA.kbStats(k).prog * 100) + '%'; }); }
  function kbStatus(k) {
    const s = LA.kbStats(k);
    return `<span data-kbp="${k.id}">${s.indexing ? C.status('EMBEDDING', 'Indexing ' + Math.round(s.prog * 100) + '%') : s.failed ? C.status('FAILED', s.failed + ' failed') : C.status('INDEXED')}</span>`;
  }
  function kbCard(k) {
    const s = LA.kbStats(k); const owner = k.scope === 'AGENT' ? LA.agent(k.owner) : null;
    return `<button class="card tap" data-go="kb-${k.id}"><div class="row" style="align-items:flex-start">${C.kbIcon(k)}<div class="grow"><div class="row between"><b style="font:600 15px/1.3 var(--font-display)" class="ellipsis">${esc(k.name)}</b>${kbStatus(k)}</div><div class="small muted ellipsis">${esc(k.desc || '')}</div></div></div>
      ${s.indexing ? `<div style="margin-top:12px" data-kbbar="${k.id}">${C.progress(s.prog, 'kb')}</div>` : ''}
      <div class="row wrap" style="gap:6px;margin-top:12px"><span class="tag">${I('file', 'icon-sm')}${s.docs} docs</span><span class="tag">${I('database', 'icon-sm')}${F.mb(s.mb)}</span><span class="tag">${I('history', 'icon-sm')}${F.ago(k.updated)}</span>${owner ? `<span class="tag agent">${I('lock', 'icon-sm')}Only ${esc(owner.name)}</span>` : `<span class="tag kb">Shared</span>`}</div></button>`;
  }

  /* ---------- create ---------- */
  let f = null;
  const PICK = [['Quarterly report Q3.pdf', 'PDF', 3.2, 24], ['Team handbook.docx', 'Word', 0.8, 31], ['notes-2026.md', 'Markdown', 0.03, null], ['parser.py', 'Python', 0.01, null], ['Meeting transcript.txt', 'Text', 0.05, null], ['Scanned contract.pdf', 'PDF', 4.1, 6]];
  LA.screens.kbForm = {
    render() {
      if (!f) f = { name: '', desc: '', scope: 'SHARED', owner: LA.S.agents[0].id, embed: 'me5-small', chunk: 400, overlap: 60, files: [], note: '', err: {} };
      const embeds = LA.S.models.filter((m) => m.purpose === 'embed');
      return `${C.topbar({ title: 'New knowledge base', sub: 'Indexed on this device', back: 'knowledge' })}
      <div class="scroll"><div class="body">
        <div class="field"><label for="k-name">Name</label><input class="input" id="k-name" maxlength="40" placeholder="e.g. Course notes — Linear algebra" value="${esc(f.name)}" data-input="name">${f.err.name ? `<span class="err">${f.err.name}</span>` : ''}</div>
        <div class="field"><label for="k-desc">Description <span class="faint">(optional)</span></label><input class="input" id="k-desc" maxlength="120" value="${esc(f.desc)}" data-input="desc"></div>
        <div class="field"><span class="label">Who can use it</span>${C.seg('Scope', [['SHARED', 'All chats and agents'], ['AGENT', 'One agent only']], f.scope, 'scope', true)}
          ${f.scope === 'AGENT' ? `<select class="select" id="k-owner" data-change="owner" style="margin-top:8px">${LA.S.agents.map((a) => `<option value="${a.id}" ${f.owner === a.id ? 'selected' : ''}>${esc(a.name)} · ${esc(a.role)}</option>`).join('')}</select><span class="hint">Agent-specific knowledge never appears in other agents’ searches.</span>` : ''}</div>
        <div class="field"><span class="label">Sources</span>
          <div class="row" style="gap:8px"><button class="btn btn-outline btn-sm" data-action="add-files">${I('upload', 'icon-sm')}Add files</button><button class="btn btn-ghost btn-sm" data-action="add-note">${I('note', 'icon-sm')}Write a note</button></div>
          ${f.files.length || f.note ? `<div class="list" style="margin-top:8px">${f.files.map((x, i) => `<div class="li">${I(x[1] === 'PDF' ? 'file' : /Python|Kotlin/.test(x[1]) ? 'code' : 'note')}<div class="grow"><div class="t small">${esc(x[0])}</div><div class="s">${x[1]} · ${F.mb(x[2])}${x[0].startsWith('Scanned') ? ' · may need OCR' : ''}</div></div>${C.ib('close', 'rm-file', 'Remove', `data-i="${i}"`)}</div>`).join('')}${f.note ? `<div class="li">${I('note')}<div class="grow"><div class="t small">Note</div><div class="s ellipsis">${esc(f.note)}</div></div></div>` : ''}</div>` : '<span class="hint">PDF (with text), DOCX, TXT, Markdown, source code, or notes. You can add more later.</span>'}
          ${f.err.files ? `<span class="err">${f.err.files}</span>` : ''}</div>
        <details class="disclosure card"><summary>Advanced indexing<span class="chev">${I('down', 'icon-sm')}</span></summary><div class="col" style="gap:14px;margin-top:12px">
          <div class="field"><label for="k-embed">Embedding model</label><select class="select" id="k-embed" data-change="embed">${embeds.map((m) => `<option value="${m.id}" ${f.embed === m.id ? 'selected' : ''} ${m.state !== 'installed' ? 'disabled' : ''}>${esc(m.name)} · ${m.dim}-dim${m.state !== 'installed' ? ' (not installed)' : ''}</option>`).join('')}</select><span class="hint">Fixed per knowledge base. Changing it later re-indexes every document.</span></div>
          <div class="field"><div class="range-row"><label for="k-chunk">Chunk size</label><output id="o-chunk">${f.chunk} tokens</output></div><input type="range" id="k-chunk" min="200" max="800" step="50" value="${f.chunk}" data-input="chunk"></div>
          <div class="field"><div class="range-row"><label for="k-ov">Overlap</label><output id="o-ov">${f.overlap} tokens</output></div><input type="range" id="k-ov" min="0" max="150" step="10" value="${f.overlap}" data-input="ov"></div>
        </div></details>
      </div></div>
      <div class="composer" style="flex-direction:row"><button class="btn btn-primary btn-block" data-action="create">${I('check')}Create and start indexing</button></div>`;
    },
    unmount() { f = null; },
    inputs: { name: (el) => { f.name = el.value; }, desc: (el) => { f.desc = el.value; }, chunk: (el) => { f.chunk = +el.value; document.getElementById('o-chunk').textContent = f.chunk + ' tokens'; }, ov: (el) => { f.overlap = +el.value; document.getElementById('o-ov').textContent = f.overlap + ' tokens'; } },
    changes: { owner: (el) => { f.owner = el.value; }, embed: (el) => { f.embed = el.value; } },
    actions: {
      scope: (el) => { f.scope = el.getAttribute('data-v'); LA.refresh(); },
      'rm-file': (el) => { f.files.splice(+el.getAttribute('data-i'), 1); LA.refresh(); },
      'add-files': () => LA.sheet({
        title: 'Choose files', body: `<div class="banner warn">${I('info')}<span>Simulated picker. The app uses the Android system picker and copies files into private storage.</span></div><div class="col" style="gap:8px">${PICK.map((p, i) => `<label class="check"><input type="checkbox" value="${i}" ${f.files.some((x) => x[0] === p[0]) ? 'checked' : ''}><div class="grow"><b class="small">${esc(p[0])}</b><div class="xs faint">${p[1]} · ${F.mb(p[2])}</div></div></label>`).join('')}</div>`,
        actions: '<button class="btn btn-primary" data-action="pick-ok">Add selected</button>',
        handlers: { 'pick-ok': () => { const sel = [...document.querySelectorAll('.sheet input[type=checkbox]:checked')].map((c) => PICK[+c.value]); f.files = sel; LA.closeSheet(); LA.refresh(); } }
      }),
      'add-note': () => LA.sheet({ title: 'Write a note', body: `<div class="field"><label for="n-body">Note</label><textarea class="textarea" id="n-body" placeholder="Anything you want agents to know…">${esc(f.note)}</textarea></div>`, actions: '<button class="btn btn-primary" data-action="note-ok">Add note</button>', handlers: { 'note-ok': () => { f.note = document.getElementById('n-body').value.trim(); LA.closeSheet(); LA.refresh(); } } }),
      create: () => {
        f.err = {};
        if (!f.name.trim()) f.err.name = 'Name the knowledge base.';
        else if (LA.S.kbs.some((k) => k.name.toLowerCase() === f.name.trim().toLowerCase())) f.err.name = 'A knowledge base with this name already exists.';
        if (!f.files.length && !f.note) f.err.files = 'Add at least one file or note.';
        if (Object.keys(f.err).length) { LA.refresh(); return; }
        const kb = { id: LA.uid('kb'), name: f.name.trim(), desc: f.desc.trim(), scope: f.scope, owner: f.scope === 'AGENT' ? f.owner : undefined, color: ['knowledge', 'model', 'agent', 'warn'][LA.S.kbs.length % 4], embed: f.embed, chunkSize: f.chunk, overlap: f.overlap, updated: Date.now() };
        LA.S.kbs.unshift(kb);
        f.files.forEach((p) => {
          const scanned = p[0].startsWith('Scanned');
          LA.S.documents.push({ id: LA.uid('doc'), kb: kb.id, title: p[0], type: p[1], sizeMB: p[2], pages: p[3], chunks: 0, status: scanned ? 'FAILED' : 'QUEUED', progress: 0, added: Date.now(), error: scanned ? 'No text layer found (scanned image). OCR is planned for a later release.' : undefined, excerpt: scanned ? [] : ['Sample passage extracted from ' + p[0] + '. In the app this shows real text from your file.', 'A second passage with headings and page numbers preserved for citations.'] });
        });
        if (f.note) LA.S.documents.push({ id: LA.uid('doc'), kb: kb.id, title: 'Note — ' + f.note.slice(0, 24), type: 'Note', sizeMB: 0.001, pages: null, chunks: 0, status: 'QUEUED', progress: 0, added: Date.now(), excerpt: [f.note] });
        if (kb.scope === 'AGENT') { const a = LA.agent(kb.owner); if (a && !a.kbs.includes(kb.id)) a.kbs.push(kb.id); }
        LA.S.kbExtra[kb.id] = { docs: 0, chunks: 0, mb: 0 };
        LA.save(); f = null; LA.go('kb-' + kb.id); LA.toast('Knowledge base created · indexing (simulated)');
      }
    }
  };

  /* ---------- KB detail ---------- */
  let kq = '';
  LA.screens.kbDetail = {
    render(id) {
      const k = LA.kb(id); if (!k) return C.topbar({ title: 'Not found', back: 'knowledge' }) + '<div class="scroll"><div class="body">' + C.empty('knowledge', 'This knowledge base was deleted', '') + '</div></div>';
      const s = LA.kbStats(k); const docs = LA.kbDocs(id); const extra = LA.S.kbExtra[id] || { docs: 0 };
      const owner = k.scope === 'AGENT' ? LA.agent(k.owner) : null;
      return `${C.topbar({ title: esc(k.name), sub: `${s.docs} documents · ${F.mb(s.mb)}`, back: 'knowledge', actions: C.ib('more', 'more', 'Knowledge base options') })}
      <div class="scroll"><div class="body">
        <div class="row wrap" style="gap:6px">${kbStatus(k)}${owner ? `<span class="tag agent">${I('lock', 'icon-sm')}Only ${esc(owner.name)}</span>` : '<span class="tag kb">Shared</span>'}<span class="tag">${esc(LA.model(k.embed).name)}</span><span class="tag">${k.chunkSize}/${k.overlap} tokens</span></div>
        ${s.indexing ? `<div class="banner info">${I('info')}<span>Indexing ${s.indexing} document${s.indexing > 1 ? 's' : ''}. You can keep chatting; indexing pauses while the model is answering.</span></div>` : ''}
        <div class="search">${I('search')}<input id="kbd-q" placeholder="Search in ${esc(k.name)}" value="${esc(kq)}" data-input="q" aria-label="Search this knowledge base"></div>
        <div class="col" id="kbd-results" style="gap:10px">${results(kq, [id])}</div>
        ${C.sectionH('Documents')}
        <div class="list" id="doc-list">${docs.map(docRow).join('')}${extra.docs ? `<div class="li small faint">+ ${extra.docs} more indexed documents (sample)</div>` : ''}${!docs.length && !extra.docs ? '<div class="li small muted">No documents yet</div>' : ''}</div>
        <div class="row" style="gap:8px"><button class="btn btn-outline grow" data-action="add">${I('plus', 'icon-sm')}Add documents</button><button class="btn btn-ghost grow" data-action="reindex-all">${I('refresh', 'icon-sm')}Re-index all</button></div>
        <button class="btn btn-tonal" data-action="ask">${I('chat', 'icon-sm')}Ask this knowledge base</button>
      </div></div>`;
    },
    unmount() { kq = ''; },
    mount(root, id) { this._sig = LA.kbDocs(id).map((d) => d.status).join(); },
    tick() {
      const id = LA.cur().param; const docs = LA.kbDocs(id); const sig = docs.map((d) => d.status).join();
      if (sig !== this._sig) { this._sig = sig; LA.refresh(); return; }
      docs.forEach((d) => { const b = document.querySelector(`[data-docbar="${d.id}"] .progress > i`); if (b) b.style.width = Math.round(d.progress * 100) + '%'; });
    },
    inputs: { q: (el, ev, id) => { kq = el.value; clearTimeout(LA._sq); LA._sq = setTimeout(() => { const r = document.getElementById('kbd-results'); if (r) r.innerHTML = results(kq, [id]); }, 180); } },
    actions: {
      add: (el, ev, id) => { const k = LA.kb(id); const n = LA.uid('doc'); LA.S.documents.push({ id: n, kb: id, title: 'Imported file ' + (LA.kbDocs(id).length + 1) + '.pdf', type: 'PDF', sizeMB: 1.4, pages: 12, chunks: 0, status: 'QUEUED', progress: 0, added: Date.now(), excerpt: ['Sample passage from an imported file.'] }); k.updated = Date.now(); LA.save(); LA.refresh(); LA.toast('Added 1 file (simulated picker)'); },
      'reindex-all': (el, ev, id) => LA.confirm({ title: 'Re-index all documents?', body: 'Each document is chunked and embedded again. This can take a while and uses battery.', ok: 'Re-index', onOk: () => { LA.kbDocs(id).forEach((d) => { if (d.status !== 'FAILED') { d.status = 'QUEUED'; d.progress = 0; } }); LA.save(); LA.refresh(); } }),
      ask: (el, ev, id) => { const k = LA.kb(id); LA.newChat({ kbs: [id], agent: k.scope === 'AGENT' ? k.owner : null, title: 'Ask ' + k.name }); },
      more: (el, ev, id) => { const k = LA.kb(id); LA.sheet({ title: esc(k.name), body: `<div class="list"><button class="li" data-action="del-kb" style="color:var(--bad)">${I('trash')}<div class="grow"><div class="t">Delete knowledge base</div><div class="s">Removes documents, chunks and vectors from this device</div></div></button></div>`, handlers: { 'del-kb': () => LA.confirm({ title: 'Delete ' + esc(k.name) + '?', body: 'All documents, chunks and vectors are permanently removed. Conversations keep their text but lose source links.', ok: 'Delete', danger: true, onOk: () => { LA.S.kbs = LA.S.kbs.filter((x) => x.id !== id); LA.S.documents = LA.S.documents.filter((d) => d.kb !== id); LA.S.agents.forEach((a) => { a.kbs = a.kbs.filter((x) => x !== id); }); LA.S.conversations.forEach((c) => { c.kbs = c.kbs.filter((x) => x !== id); }); LA.save(); LA.go('knowledge'); LA.toast('Knowledge base deleted'); } }) } }); }
    }
  };
  function docRow(d) {
    const ic = d.type === 'PDF' ? 'file' : /Kotlin|Python/.test(d.type) ? 'code' : 'note';
    return `<button class="li" data-go="doc-${d.id}">${I(ic)}<div class="grow"><div class="t small ellipsis">${esc(d.title)}</div><div class="s">${esc(d.type)} · ${F.mb(d.sizeMB)}${d.pages ? ' · ' + d.pages + ' pages' : ''}${d.status === 'INDEXED' ? ' · ' + d.chunks + ' chunks' : ''}</div>${ACTIVE.includes(d.status) ? `<div style="margin-top:6px" data-docbar="${d.id}">${C.progress(d.progress, 'kb thin')}</div>` : ''}</div>${C.status(d.status)}</button>`;
  }

  /* ---------- document detail ---------- */
  LA.screens.doc = {
    render(id) {
      const d = LA.doc(id); if (!d) return C.topbar({ title: 'Not found', back: 'knowledge' }) + '<div class="scroll"><div class="body">' + C.empty('file', 'This document was removed', '') + '</div></div>';
      const k = LA.kb(d.kb); const stages = ['EXTRACTING', 'CHUNKING', 'EMBEDDING', 'INDEXED']; const at = d.status === 'QUEUED' ? -1 : stages.indexOf(d.status);
      const chunks = d.excerpt.map((p, i) => ({ n: 100 + hash(d.id) % 300 + i, p, tokens: Math.round(p.length / 3.8) + 280, where: d.pages ? 'p. ' + (2 + i * 2) : 'section ' + (i + 1) }));
      return `${C.topbar({ title: esc(d.title), sub: esc(k.name), back: 'kb-' + d.kb, actions: C.ib('trash', 'remove', 'Remove document') })}
      <div class="scroll"><div class="body">
        <div class="row wrap" style="gap:6px">${C.status(d.status)}<span class="tag">${esc(d.type)}</span>${C.sample()}</div>
        ${d.status === 'FAILED' ? `<div class="banner bad">${I('alert')}<span>${esc(d.error)}</span></div>` : ''}
        <div class="card"><div class="pipeline">${['Extract', 'Chunk', 'Embed', 'Indexed'].map((t, i) => `<div class="stage-i ${d.status === 'FAILED' ? '' : i < at || d.status === 'INDEXED' ? 'done' : i === at ? 'now' : ''}"><i></i>${t}</div>`).join('')}</div></div>
        <div class="card"><dl class="kv"><dt>Size</dt><dd>${F.mb(d.sizeMB)}</dd>${d.pages ? `<dt>Pages</dt><dd>${d.pages}</dd>` : ''}<dt>Chunks</dt><dd>${d.chunks || '—'}</dd><dt>Embedding</dt><dd>${esc(LA.model(k.embed).name)}</dd><dt>Chunking</dt><dd>${k.chunkSize} tokens, ${k.overlap} overlap</dd><dt>Added</dt><dd>${F.ago(d.added)}</dd><dt>Stored at</dt><dd class="mono">files/docs/${hash(d.id).toString(16).slice(0, 8)}…</dd></dl></div>
        ${d.excerpt.length ? C.sectionH('Extracted text') + `<div class="card small muted" style="line-height:1.6">${esc(d.excerpt.join(' '))}</div>` : ''}
        ${d.status === 'INDEXED' && chunks.length ? C.sectionH('Chunks') + `<div class="col" style="gap:14px">${chunks.map((c) => `<div class="chunk"><div class="row between xs faint"><span class="mono">chunk:${c.n}</span><span>${c.where} · ${c.tokens} tokens</span></div><p>${esc(c.p)}</p></div>`).join('')}${d.chunks > chunks.length ? `<span class="xs faint">+ ${d.chunks - chunks.length} more chunks</span>` : ''}</div>` : ''}
      </div></div>
      <div class="composer" style="flex-direction:row;gap:10px"><button class="btn btn-outline grow" data-action="reindex">${I('refresh', 'icon-sm')}Re-index</button><button class="btn btn-primary grow" data-action="ask" ${d.status !== 'INDEXED' ? 'disabled' : ''}>${I('chat', 'icon-sm')}Ask about it</button></div>`;
    },
    mount(root, id) { const d = LA.doc(id); this._sig = d ? d.status : ''; },
    tick() { const d = LA.doc(LA.cur().param); if (d && d.status !== this._sig) { this._sig = d.status; LA.refresh(); } },
    actions: {
      reindex: (el, ev, id) => { const d = LA.doc(id); if (d.status === 'FAILED' && d.error && d.error.includes('OCR')) { LA.toast('Needs OCR, planned for a later release'); return; } d.status = 'QUEUED'; d.progress = 0; LA.save(); LA.refresh(); LA.toast('Re-indexing (simulated)'); },
      ask: (el, ev, id) => { const d = LA.doc(id); const k = LA.kb(d.kb); LA.newChat({ kbs: [d.kb], attachments: [d.id], agent: k.scope === 'AGENT' ? k.owner : null, title: 'About ' + d.title.slice(0, 30) }); },
      remove: (el, ev, id) => { const d = LA.doc(id); LA.confirm({ title: 'Remove document?', body: `${esc(d.title)} and its ${d.chunks} chunks are deleted from this device.`, ok: 'Remove', danger: true, onOk: () => { LA.S.documents = LA.S.documents.filter((x) => x.id !== id); LA.save(); LA.go('kb-' + d.kb); LA.toast('Document removed'); } }); }
    }
  };
})();
