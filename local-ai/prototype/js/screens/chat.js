/* Conversation history and AI chat with simulated streaming. */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc; const F = LA.fmt;
  LA.screens = LA.screens || {};

  LA.newChat = function (o) {
    const c = {
      id: LA.uid('c'), title: o.title || 'New chat', model: LA.S.activeModel, agent: o.agent || null, kbs: o.kbs || [],
      attachments: o.attachments || [], updated: Date.now(), agentMode: !!o.agentMode, messages: [],
      system: o.agent && o.agent !== 'supervisor' ? LA.agent(o.agent).prompt : 'You are a helpful assistant running locally on this device. Be concise.'
    };
    LA.S.conversations.unshift(c); LA.save(); LA.go('chat-' + c.id);
  };

  /* ---------- canned, clearly simulated responses ---------- */
  function respond(c, text, variant) {
    const t = text.toLowerCase(); const hasKb = (id) => c.kbs.includes(id);
    const recent = c.messages.filter((m) => m.role === 'user').slice(-2).find((m) => m.photo);
    if (recent && LA.isVision(c.model || LA.S.activeModel)) return { text: recent.photo === 'receipt' && /expensive|most|costly/.test(t) ? 'The **basmati rice (5 kg) at 649**, about two-thirds of the bill.' : 'Looking at the same photo again: ' + LA.photoAnswer(recent.photo, text).split('\n')[0] };
    if (hasKb('kb-finance') && /deposit|rent|lease|notice|insurance|clause/.test(t)) return {
      text: variant ? 'Short version: **two months’ rent** as deposit, refunded **within 30 days** after you move out, minus documented deductions [chunk:7]. Give **60 days’ written notice** before leaving [chunk:12].' : 'According to your lease, the security deposit equals **two months’ rent** and is refunded **within 30 days** of the tenancy ending, minus documented deductions [chunk:7].\n\nTermination needs **not less than 60 days’ written notice** from either side [chunk:12]. Minor repairs under 2,000 are your responsibility [chunk:15].',
      sources: [{ n: 7, doc: 'doc-lease', title: 'Lease agreement — Flat 4B.pdf', where: 'p. 4 · Clause 7.2', score: 0.86 }, { n: 12, doc: 'doc-lease', title: 'Lease agreement — Flat 4B.pdf', where: 'p. 6 · Clause 9.1', score: 0.71 }, { n: 15, doc: 'doc-lease', title: 'Lease agreement — Flat 4B.pdf', where: 'p. 7 · Clause 11', score: 0.58 }]
    };
    if (hasKb('kb-research')) return {
      text: '### What limits LLMs on phones\n- **Memory bandwidth and DRAM capacity**, not raw compute, bound generation speed. Loading parameters from flash on demand is one mitigation [chunk:21].\n- **KV cache growth** with context length; paging the cache into fixed blocks avoids fragmentation [chunk:44].\n- **Architecture choices** for small models: deep-and-thin networks and grouped-query attention reduce memory with little quality loss [chunk:33].\n\nFor this app, that means quantised weights, a quantised KV cache and modest default context sizes.',
      sources: [{ n: 21, doc: 'doc-flash', title: 'LLM in a flash — inference with limited memory.pdf', where: 'p. 2 · Introduction', score: 0.82 }, { n: 44, doc: 'doc-paged', title: 'PagedAttention — memory management for LLM serving.pdf', where: 'p. 4 · §3', score: 0.77 }, { n: 33, doc: 'doc-mobilellm', title: 'MobileLLM — sub-billion parameter models.pdf', where: 'p. 3 · §2.2', score: 0.74 }]
    };
    if (/kotlin|code|compose|flow|function|class|interface|android/.test(t)) return {
      text: (variant ? 'Here is a leaner version:' : 'A minimal, cancellable streaming API looks like this:') + '\n\n```kotlin\ninterface InferenceEngine {\n    fun generate(request: GenerationRequest): Flow<TokenEvent>\n    suspend fun embed(texts: List<String>): List<FloatArray>\n}\n\nsealed interface TokenEvent {\n    data class Delta(val text: String) : TokenEvent\n    data class Done(val tokensPerSecond: Float) : TokenEvent\n}\n```\n\nCollect it in the ViewModel with `sample(50.milliseconds)` so Compose recomposes about 20 times per second. Cancelling the collecting coroutine aborts native decoding.'
    };
    if (/\d\s*[\*x×\+\-\/\^]\s*\d/.test(t)) {
      const expr = (text.match(/[\d\s\.\+\-\*\/\^\(\)×x%]+/g) || ['']).sort((a, b) => b.length - a.length)[0].replace(/x/g, '*');
      let r; try { r = LA.calc(expr); } catch (e) { r = null; }
      return { text: r == null ? 'I could not parse that expression. Try something like `(1250 * 12) * 0.18`.' : 'Using the **Calculator** tool: `' + expr.trim() + '` = **' + (+r.toFixed(6)).toLocaleString('en-US') + '**', tool: 'calculator' };
    }
    return { text: variant ? 'Here is another take. I am running fully on this device, so I can help with writing, planning, explanations and questions about your documents, without sending anything to a server.' : 'Happy to help. I am running on this device, so this conversation never leaves your phone.\n\nTip: select a **knowledge base** below to get answers grounded in your own documents, with tappable sources.' };
  }

  /* ---------- streaming simulation ---------- */
  let stream = null;
  function stopStream(mark) {
    if (!stream) return;
    clearInterval(stream.timer);
    const c = LA.conv(stream.convId); const m = c && c.messages[stream.idx];
    if (m) { m.streaming = false; if (mark) m.stopped = true; }
    stream = null; LA.save();
  }
  function startStream(c, idx, resp) {
    const m = c.messages[idx]; m.text = ''; m.streaming = true; m.sources = resp.sources || null; m.tool = resp.tool || null; m.stopped = false;
    m.model = c.model || LA.S.activeModel; m.tps = +(LA.device().genTps * (0.9 + Math.random() * 0.2)).toFixed(1);
    const tokens = resp.text.match(/\s*\S+|\n/g) || [];
    stream = { convId: c.id, idx, tokens, pos: 0 };
    const tick = () => {
      if (!stream) return;
      const n = 1 + Math.floor(Math.random() * 2);
      m.text += tokens.slice(stream.pos, stream.pos + n).join(''); stream.pos += n;
      const el = document.querySelector(`.msg[data-i="${idx}"] .md`);
      if (el) el.innerHTML = LA.md(m.text) + '<span class="cursor"></span>';
      const sc = document.querySelector('.scroll'); if (sc && sc.scrollHeight - sc.scrollTop - sc.clientHeight < 140) sc.scrollTop = sc.scrollHeight;
      if (stream.pos >= tokens.length) { m.text = resp.text; stopStream(false); c.updated = Date.now(); if (LA.S.settings.readAloud) LA.speech.speak(m.text, c.id + ':' + idx); if (LA.cur().hash === 'chat-' + c.id) LA.refresh(); }
    };
    stream.timer = setInterval(tick, Math.round(1000 / m.tps));
  }

  function ctxUse(c) {
    const m = LA.model(c.model || LA.S.activeModel) || LA.model(LA.S.activeModel);
    const used = Math.round(((c.system || '').length + c.messages.reduce((s, x) => s + (x.text || '').length, 0)) / 3.6) + (c.kbs.length ? 1400 : 0) + 180;
    return { used, max: LA.profile(m).ctx };
  }

  function msgHtml(c, m, i, isLast) {
    if (m.role === 'user') return `<div class="msg user" data-i="${i}">${m.photo ? `<img class="photo" src="${LA.photoSrc(m.photo)}" alt="Attached photo">` : ''}${m.text ? `<div class="bubble">${m.voice ? LA.icon('mic', 'icon-sm') + ' ' : ''}${esc(m.text)}</div>` : ''}</div>`;
    if (m.noVision) return `<div class="msg ai" data-i="${i}"><div class="banner warn">${LA.icon('alert')}<span>${esc((LA.model(m.model) || {}).name || 'This model')} can't see images. Use a vision model such as <b>SmolVLM 500M</b> (fits 4–6 GB phones).</span></div><div class="row" style="gap:8px"><button class="btn btn-tonal btn-sm" data-action="use-vision">${LA.icon('image', 'icon-sm')}Use SmolVLM 500M and retry</button></div></div>`;
    const model = LA.model(m.model) || LA.model(LA.S.activeModel);
    const ag = c.agent && c.agent !== 'supervisor' ? LA.agent(c.agent) : null;
    let wf = '';
    if (m.workflow) {
      const w = LA.wf.summary();
      const live = m.live && w.status !== 'idle';
      wf = `<div class="wf-card"><div class="row between"><b class="small">${I('workflow', 'icon-sm')} Supervisor workflow</b>${live ? C.status(w.status === 'done' ? 'completed' : w.status === 'cancelled' ? 'cancelled' : w.status === 'partial' ? 'failed' : 'running', w.label) : C.status('completed', '7 steps')}</div><div class="wf-mini">${(live ? w.nodes : Array(8).fill('done')).map((s) => `<i class="${s}"></i>`).join('')}</div><button class="btn btn-tonal btn-sm" data-go="workflow">${I('eye', 'icon-sm')}Open workflow</button></div>`;
    }
    const sources = m.sources && !m.streaming ? `<div class="sources">${m.sources.map((s) => `<button class="source" data-action="cite" data-n="${s.n}"><span class="n">${s.n}</span><span class="grow ellipsis">${esc(s.title)}</span><span class="faint xs" style="flex:none">${esc(s.where)}</span></button>`).join('')}</div>` : '';
    const speaking = LA.speech.speakingId === c.id + ':' + i;
    const actions = m.streaming ? '' : `<div class="msg-actions">${C.ib('copy', 'copy-msg', 'Copy response', `data-i="${i}"`)}${C.ib(speaking ? 'stop' : 'volume', 'speak-msg', speaking ? 'Stop reading' : 'Read aloud', `data-i="${i}"${speaking ? ' style="color:var(--accent)"' : ''}`)}${C.ib('doc', 'save-msg', 'Save as PDF or Word', `data-i="${i}"`)}${isLast ? C.ib('refresh', 'regen', 'Regenerate response') : ''}<span class="sim-note" style="margin-left:6px">${I('info', 'icon-sm')}Simulated · ${m.tps || '—'} tok/s</span></div>`;
    return `<div class="msg ai" data-i="${i}"><div class="who">${ag ? C.avatar(ag, 'sm') : ''}<b>${ag ? esc(ag.name) : esc(model.name)}</b>${m.tool ? `<span class="tag">${I('calc', 'icon-sm')}calculator</span>` : ''}</div>${wf}<div class="md">${LA.md(m.text || '')}${m.streaming ? '<span class="cursor"></span>' : ''}${m.stopped ? '<p class="small faint">Generation stopped.</p>' : ''}</div>${sources}${actions}</div>`;
  }

  /* Dictation: the browser's speech recognizer when present, otherwise a simulated phrase typed in. */
  let dictating = false; let rec = null; let dictTimer = null;
  function stopDictation() { dictating = false; clearInterval(dictTimer); dictTimer = null; try { rec && rec.stop(); } catch (e) { /* ignore */ } rec = null; }
  function startDictation() {
    const ta = document.getElementById('composer-input'); const base = ta ? ta.value.trim() : '';
    dictating = true; LA.refresh();
    const set = (t) => { const el = document.getElementById('composer-input'); if (el) el.value = (base + ' ' + t).trim(); };
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (SR) {
      try {
        rec = new SR(); rec.interimResults = true; if (LA.S.settings.voiceLang) rec.lang = LA.S.settings.voiceLang;
        rec.onresult = (e) => set(Array.from(e.results).map((r) => r[0].transcript).join(' '));
        rec.onend = () => { if (dictating) { stopDictation(); LA.refresh(); } };
        rec.onerror = () => { try { rec.onend = null; } catch (e) { /* ignore */ } rec = null; simulate(); };
        let started = false; rec.onaudiostart = () => { started = true; };
        rec.start();
        // Some browsers expose a recognizer that never opens the microphone: fall back after 1.5 s.
        const mine = rec; setTimeout(() => { if (!started && rec === mine && dictating) { try { mine.onend = null; mine.onerror = null; mine.abort(); } catch (e) { /* ignore */ } rec = null; simulate(); } }, 1500);
        return;
      } catch (e) { rec = null; }
    }
    simulate();
    // No usable recognizer in this browser (or it needs a network): type a sample phrase instead.
    function simulate() {
      if (!dictating) return;
      const phrase = 'What does this photo show?'.split(' '); let i = 0;
      dictTimer = setInterval(() => { i++; set(phrase.slice(0, i).join(' ')); if (i >= phrase.length) { stopDictation(); LA.refresh(); LA.toast('Dictation (simulated)'); } }, 260);
    }
  }

  const toBottom = () => { const sc = document.querySelector('.scroll'); if (sc) sc.scrollTop = sc.scrollHeight; };
  let lastModelState = null;
  LA.screens.chat = {
    render(id) {
      const c = LA.conv(id);
      if (!c) return C.topbar({ title: 'Chat not found', back: 'chat' }) + `<div class="scroll"><div class="body">${C.empty('chat', 'This conversation was deleted', 'Start a new one from the chat list.', '<button class="btn btn-primary" data-go="chat">Go to chats</button>')}</div></div>`;
      const m = LA.model(c.model || LA.S.activeModel) || LA.model(LA.S.activeModel);
      const ag = c.agent === 'supervisor' ? null : c.agent ? LA.agent(c.agent) : null;
      const st = LA.modelState(); const busy = !!(stream && stream.convId === c.id);
      const u = ctxUse(c);
      const sub = `${esc(m.name)} · ${st === 'loaded' ? 'ready' : st === 'loading' ? 'loading…' : 'not loaded'}`;
      const empty = `<div class="chat-empty">${ag ? C.avatar(ag, 'lg') : ''}<h2>${c.agentMode ? 'What should your agents work on?' : ag ? 'Ask ' + esc(ag.name) : 'What can I help with?'}</h2><span class="small faint">${c.kbs.length ? 'Answers will cite your documents.' : 'Private and offline · stays on this phone'}</span>
        <div class="starters">${[['camera', 'Explain a photo', 'attach'], ['shield', 'Is this message a scam?', 'go-scam'], ['doc', 'Write a letter or report', 'go-studio'], ['volume', 'Read a document to me', 'go-read']].map(([ic, t, a]) => `<button data-action="${a}">${I(ic)}<span>${t}</span></button>`).join('')}</div>
        <div class="chips" style="justify-content:center">${(c.agentMode ? ['Compare llama.cpp and ExecuTorch and draft a Kotlin interface'] : c.kbs.includes('kb-finance') ? ['When do I get my deposit back?', 'What is the notice period?'] : ['Explain Kotlin Flow sample()', 'What is 38000 * 2 * 0.9?']).map((t) => `<button class="chip sm" data-action="suggest" data-t="${esc(t)}">${esc(t)}</button>`).join('')}</div></div>`;
      const msgs = c.messages.length ? c.messages.map((x, i) => msgHtml(c, x, i, i === c.messages.length - 1)).join('') : empty;
      const kbChip = c.kbs.length ? `<button class="chip sm on" data-action="pick-kb">${I('knowledge', 'icon-sm')}${c.kbs.length === 1 ? esc(LA.kb(c.kbs[0]).name) : c.kbs.length + ' knowledge bases'}</button>` : '';
      const agentChip = c.agentMode || ag ? `<button class="chip sm on" data-action="pick-agent">${I('agents', 'icon-sm')}${c.agentMode ? 'Agent mode: Supervisor' : esc(ag.name)}</button>` : '';
      const att = (c.attachments || []).map((d) => `<span class="chip sm on">${I('file', 'icon-sm')}${esc(LA.doc(d) ? LA.doc(d).title : d)}</span>`).join('');
      const ctx = kbChip + agentChip + att;
      const sendVisible = !!c.pendingPhoto;
      return `<header class="gpt-top"><button class="ibtn round" data-action="drawer" aria-label="Chats and menu">${I('menu')}</button>
          <button class="model-pill" data-action="chat-model"><b>${ag ? esc(ag.name) : esc(m.name.replace(' Instruct', ''))}</b><small>${st === 'loaded' ? 'ready' : st === 'loading' ? 'loading…' : 'not loaded'}</small>${I('down', 'icon-sm')}</button>
          <span class="row" style="gap:2px">${C.ib('more', 'chat-more', 'Conversation options')}<button class="ibtn round" data-action="new-here" aria-label="New chat">${I('edit')}</button></span></header>
        <div class="scroll"><div class="chat-scroll">${st !== 'loaded' && c.messages.length ? `<div class="banner ${st === 'loading' ? 'info' : 'warn'}">${I(st === 'loading' ? 'info' : 'alert')}<span>${st === 'loading' ? 'Loading model into memory…' : 'No model is loaded. Sending will load ' + esc(m.name) + ' first.'}</span></div>` : ''}${msgs}</div></div>
        <div class="composer gpt">${ctx ? `<div class="chips scroller">${ctx}</div>` : ''}
          ${c.pendingPhoto ? `<div class="pending-photo"><img src="${LA.photoSrc(c.pendingPhoto)}" alt="Photo to send"><div class="grow"><b class="small">Photo attached</b><div class="xs faint">Ask about it, or just send. Text in the photo is read on the phone.</div></div>${C.ib('close', 'photo-remove', 'Remove photo')}</div>` : ''}
          <div class="pill ${sendVisible ? 'has-text' : ''}"><button class="ibtn" data-action="attach" aria-label="Add photo, files and more">${I('plus')}</button><textarea id="composer-input" rows="1" placeholder="${dictating ? 'Listening…' : c.agentMode ? 'Describe a task for the agents…' : c.pendingPhoto ? 'Ask about the photo' : 'Ask LOCAL AI'}" aria-label="Message" data-input="grow"></textarea>
          ${busy ? '' : `<button class="ibtn mic ${dictating ? 'on' : ''}" data-action="dictate" aria-label="${dictating ? 'Stop dictation' : 'Dictate'}">${I('mic')}</button>`}
          ${busy ? `<button class="sendbtn stop" data-action="stop" aria-label="Stop generating">${I('stop')}</button>` : `<button class="sendbtn send" data-action="send" aria-label="Send">${I('send')}</button><button class="sendbtn voicebtn" data-go="voice-${c.id}" aria-label="Voice mode">${I('wave')}</button>`}</div>
          <div class="ctxbar"><span>Context</span>${C.progress(u.used / u.max, u.used / u.max > 0.8 ? 'warn' : 'thin')}<span>${F.num(u.used)} / ${F.ctx(u.max)}</span></div></div>`;
    },
    mount(root, id, same) {
      const sc = root.querySelector('.scroll'); if (sc && !same) sc.scrollTop = sc.scrollHeight;
      const ta = root.querySelector('#composer-input');
      if (ta) ta.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); LA.screens.chat.actions.send(null, e, id); } });
    },
    unmount() { /* keep streaming in background; it finishes into the stored conversation */ },
    tick() {
      const ms = LA.modelState(); let need = false;
      if (ms !== lastModelState) { need = lastModelState !== null; lastModelState = ms; }
      const c = LA.conv(LA.cur().param);
      if (c && c.messages.some((m) => m.workflow) && LA.wf.dirty) { LA.wf.dirty = false; need = true; }
      if (need && !stream) LA.refresh();
    },
    inputs: { grow: (el) => { el.style.height = 'auto'; el.style.height = Math.min(120, el.scrollHeight) + 'px'; const p = el.closest('.pill'); if (p) p.classList.toggle('has-text', !!el.value.trim() || !!document.querySelector('.pending-photo')); } },
    actions: {
      send: (el, ev, id) => {
        const c = LA.conv(id); const ta = document.getElementById('composer-input'); const text = ta.value.trim();
        const photo = c.pendingPhoto || null;
        if (!text && !photo) { ta.focus(); return; }
        if (stream) return;
        stopDictation();
        if (LA.modelState() === 'unloaded') LA.loadModel(c.model || LA.S.activeModel);
        c.messages.push(photo ? { role: 'user', text: text || 'What is in this photo? Explain it.', photo } : { role: 'user', text }); c.pendingPhoto = null;
        if (c.title === 'New chat') c.title = photo ? 'Photo: ' + (LA.PHOTOS[photo] ? LA.PHOTOS[photo].title.toLowerCase() : 'my photo') : text.slice(0, 42) + (text.length > 42 ? '…' : '');
        if (photo) {
          const mid = c.model || LA.S.activeModel;
          if (!LA.isVision(mid)) { c.messages.push({ role: 'ai', noVision: true, model: mid, text: '' }); ta.value = ''; LA.save(); LA.refresh(); toBottom(); return; }
          c.messages.push({ role: 'ai', text: '' }); startStream(c, c.messages.length - 1, { text: LA.photoAnswer(photo, text) }); ta.value = ''; LA.refresh(); toBottom(); return;
        }
        c.updated = Date.now();
        if (c.agentMode) {
          c.messages.push({ role: 'ai', workflow: true, live: true, text: '', model: c.model });
          LA.wf.start({ request: text, onDone: (answer) => { const m = c.messages.find((x) => x.live); if (m) { m.text = answer; m.live = false; m.sources = [{ n: 31, doc: 'doc-arch', title: 'architecture.md', where: '§ Inference', score: 0.79 }]; } LA.save(); } });
          ta.value = ''; LA.save(); LA.refresh(); toBottom(); return;
        }
        c.messages.push({ role: 'ai', text: '' }); const idx = c.messages.length - 1;
        startStream(c, idx, respond(c, text, 0)); ta.value = ''; LA.refresh(); toBottom();
      },
      stop: () => { stopStream(true); LA.refresh(); LA.toast('Stopped'); },
      regen: (el, ev, id) => {
        const c = LA.conv(id); if (stream) return;
        const idx = c.messages.length - 1; const u = c.messages[idx - 1];
        if (!u || c.messages[idx].workflow) { LA.toast('Re-run the workflow from the workflow screen'); return; }
        c.regen = (c.regen || 0) + 1; startStream(c, idx, respond(c, u.text, c.regen % 2)); LA.refresh();
      },
      'copy-msg': (el, ev, id) => { const c = LA.conv(id); LA.copy(c.messages[+el.getAttribute('data-i')].text.replace(/\[chunk:(\d+)\]/g, '[$1]')); },
      suggest: (el) => { const ta = document.getElementById('composer-input'); ta.value = el.getAttribute('data-t'); ta.focus(); },
      cite: (el, ev, id) => {
        const c = LA.conv(id); const n = +el.getAttribute('data-n');
        const s = c.messages.flatMap((m) => m.sources || []).find((x) => x.n === n);
        if (!s) return;
        const d = LA.doc(s.doc);
        LA.sheet({ title: 'Source ' + n, body: `<div class="row">${I('file')}<div class="grow"><b>${esc(s.title)}</b><div class="small muted">${esc(s.where)} · relevance ${s.score.toFixed(2)}</div></div></div><div class="chunk"><p>${esc(d && d.excerpt[0] ? d.excerpt[0] : 'Excerpt unavailable.')}</p></div><p class="small faint" style="margin:0">Retrieved by hybrid search (vector + keyword, fused with RRF). Sample data.</p>`, actions: `<button class="btn btn-primary" data-go="doc-${s.doc}">Open document</button>` });
      },
      'chat-model': (el, ev, id) => LA.modelPicker((mid) => { const c = LA.conv(id); c.model = mid; LA.save(); }),
      'pick-kb': (el, ev, id) => {
        const c = LA.conv(id);
        const list = LA.S.kbs.filter((k) => k.scope !== 'AGENT' || k.owner === c.agent);
        LA.sheet({
          title: 'Knowledge bases', body: `<p class="small muted" style="margin:0">Answers will cite passages from the selected knowledge bases.</p><div class="col">${list.map((k) => { const s = LA.kbStats(k); const on = c.kbs.includes(k.id); return `<label class="check ${on ? 'on' : ''}"><input type="checkbox" value="${k.id}" ${on ? 'checked' : ''} data-change="kb-toggle"><div class="grow"><b>${esc(k.name)}</b><div class="small muted">${s.docs} documents${k.scope === 'AGENT' ? ' · agent-specific' : ''}</div></div></label>`; }).join('')}</div>${LA.S.kbs.some((k) => k.scope === 'AGENT' && k.owner !== c.agent) ? '<p class="xs faint" style="margin:0">Agent-specific knowledge bases appear only when chatting with their agent.</p>' : ''}`,
          actions: '<button class="btn btn-primary" data-action="close-sheet">Done</button>',
          handlers: { 'kb-toggle': (cb) => { c.kbs = cb.checked ? [...new Set([...c.kbs, cb.value])] : c.kbs.filter((x) => x !== cb.value); cb.closest('.check').classList.toggle('on', cb.checked); LA.save(); }, 'close-sheet': () => { LA.closeSheet(); LA.refresh(); } }
        });
      },
      'pick-agent': (el, ev, id) => {
        const c = LA.conv(id);
        const opt = (v, t, s, on, av) => `<button class="li" data-action="agent-set" data-v="${v}">${av}<div class="grow"><div class="t">${t}</div><div class="s">${s}</div></div>${on ? I('check') : ''}</button>`;
        LA.sheet({
          title: 'Agent mode', body: `<div class="list">${opt('', 'Off', 'Plain chat with the model', !c.agentMode && !c.agent, `<span class="avatar sm" style="background:var(--surface-3)">${I('chat')}</span>`)}${opt('supervisor', 'Supervisor workflow', 'Plans tasks and delegates to agents', c.agentMode, `<span class="avatar sm" style="background:var(--hue-agent-soft);color:var(--hue-agent)">${I('workflow')}</span>`)}${LA.S.agents.map((a) => opt(a.id, esc(a.name), esc(a.role), c.agent === a.id && !c.agentMode, C.avatar(a, 'sm'))).join('')}</div>`,
          handlers: { 'agent-set': (b) => { const v = b.getAttribute('data-v'); c.agentMode = v === 'supervisor'; c.agent = v || null; if (v && v !== 'supervisor') { const a = LA.agent(v); c.system = a.prompt; c.kbs = [...new Set([...c.kbs, ...a.kbs])]; } LA.save(); LA.closeSheet(); LA.refresh(); } }
        });
      },
      attach: (el, ev, id) => {
        const c = LA.conv(id);
        const row = (ic, t, s2, a) => `<button class="li" data-action="${a}">${I(ic)}<div class="grow"><div class="t">${t}</div><div class="s">${s2}</div></div></button>`;
        const extra = `<div class="eyebrow" style="margin-top:6px">For this chat</div><div class="list">${row('agents', 'Agent mode', c.agentMode ? 'Supervisor' : c.agent ? esc(LA.agent(c.agent).name) : 'Off · plain chat with the model', 'pick-agent')}${row('knowledge', 'Knowledge bases', c.kbs.length ? 'On · answers cite your documents' : 'Answer from your documents', 'pick-kb')}${row('shield', 'Scam check', 'Is a message or screenshot a fraud?', 'go-scam')}${row('volume', 'Read it to me', 'Photo of a letter, bill or label, read aloud', 'go-read')}${row('doc', 'Documents', 'Write or convert PDF and Word', 'go-studio')}</div>`;
        LA.photoSheet((p) => { c.pendingPhoto = p; LA.save(); LA.refresh(); }, () => LA.screens.chat.actions['attach-doc'](el, ev, id), extra);
      },
      drawer: (el, ev, id) => LA.chatDrawer(id),
      'new-here': () => LA.newChat({}),
      'go-scam': () => { LA.closeSheet(); LA.go('scam'); }, 'go-studio': () => { LA.closeSheet(); LA.go('studio'); }, 'go-read': () => { LA.closeSheet(); LA.go('read'); },
      'photo-remove': (el, ev, id) => { const c = LA.conv(id); c.pendingPhoto = null; LA.save(); LA.refresh(); },
      'use-vision': (el, ev, id) => {
        const c = LA.conv(id); const vm = LA.model('smolvlm-500m');
        c.model = vm.id; LA.loadModel(vm.id);
        const err = c.messages.length - 1; const u = c.messages[err - 1];
        c.messages[err] = { role: 'ai', text: '' }; LA.refresh(); startStream(c, err, { text: LA.photoAnswer(u.photo, u.text) });
      },
      dictate: () => { if (dictating) { stopDictation(); LA.refresh(); return; } startDictation(); },
      'speak-msg': (el, ev, id) => {
        const c = LA.conv(id); const i = +el.getAttribute('data-i'); const key = c.id + ':' + i;
        if (LA.speech.speakingId === key) { LA.speech.stop(); LA.refresh(); return; }
        LA.speech.speak(c.messages[i].text, key, () => { if (LA.cur().key === 'chat') LA.refresh(); }); LA.refresh();
        if (!LA.speech.canSpeak()) LA.toast('Reading aloud (simulated: this browser has no speech voice)');
      },
      'save-msg': (el, ev, id) => { const c = LA.conv(id); LA.saveAsSheet(c.messages[+el.getAttribute('data-i')].text.replace(/\[chunk:(\d+)\]/g, '[$1]'), c.title); },
      'attach-doc': (el, ev, id) => {
        const c = LA.conv(id);
        const files = [['Offer letter.pdf', '2.4 MB'], ['Meeting notes.docx', '180 KB'], ['main.kt', '12 KB']];
        LA.sheet({
          title: 'Attach a document', body: `<div class="banner warn">${I('info')}<span>Simulated file picker. In the app this opens the Android system picker; the file is copied into private storage and indexed for this conversation only.</span></div><div class="list">${files.map(([n, s]) => `<button class="li" data-action="att" data-n="${esc(n)}">${I('file')}<div class="grow"><div class="t">${esc(n)}</div><div class="s">${s}</div></div></button>`).join('')}</div>`,
          handlers: { att: (b) => { c.attachments = [...(c.attachments || []), b.getAttribute('data-n')]; LA.save(); LA.closeSheet(); LA.refresh(); LA.toast('Attached and indexed for this chat (simulated)'); } }
        });
      },
      'chat-more': (el, ev, id) => {
        const c = LA.conv(id);
        LA.sheet({
          title: 'Conversation', body: `<div class="field"><label for="sys-prompt">System prompt</label><textarea class="textarea" id="sys-prompt">${esc(c.system || '')}</textarea><span class="hint">Applies to the next message. Agents provide their own system prompt.</span></div><div class="field"><label for="conv-title">Title</label><input class="input" id="conv-title" value="${esc(c.title)}"></div>`,
          actions: `<button class="btn btn-danger" data-action="del-conv">${I('trash', 'icon-sm')}Delete</button><span class="grow"></span><button class="btn btn-primary" data-action="save-conv">Save</button>`,
          handlers: {
            'save-conv': () => { c.system = document.getElementById('sys-prompt').value; c.title = document.getElementById('conv-title').value.trim() || c.title; LA.save(); LA.closeSheet(); LA.refresh(); LA.toast('Saved'); },
            'del-conv': () => LA.confirm({ title: 'Delete conversation?', body: 'Messages and their source links are removed from this device. This cannot be undone.', ok: 'Delete', danger: true, onOk: () => { stopStream(false); LA.S.conversations = LA.S.conversations.filter((x) => x.id !== c.id); LA.save(); LA.go('chat'); LA.toast('Conversation deleted'); } })
          }
        });
      }
    }
  };

  /* ---------- side drawer: search, sections, pinned and recent chats ---------- */
  LA.chatDrawer = function (currentId, instant) {
    document.querySelectorAll('.drawer-layer').forEach((n) => n.remove());
    const dev = document.querySelector('.device'); const w = document.createElement('div'); w.className = 'drawer-layer' + (instant ? ' instant' : '');
    let query = ''; let searching = false;
    const close = () => w.remove();
    const paint = () => {
      const all = LA.S.conversations.slice().sort((a, b) => b.updated - a.updated).filter((c) => c.messages.length || c.id === currentId)
        .filter((c) => !query || c.title.toLowerCase().includes(query.toLowerCase()));
      const row = (c) => `<button class="d-chat ${c.id === currentId ? 'on' : ''}" data-d="open" data-id="${c.id}">${I('chat', 'icon-sm')}<span class="ellipsis">${esc(c.title)}</span><i data-d="menu" data-id="${c.id}" aria-label="Chat options">${I('more', 'icon-sm')}</i></button>`;
      const secs = [['home', 'Home', 'home'], ['camera', 'Ask a photo', 'photo'], ['shield', 'Scam check', 'scam'], ['volume', 'Read it to me', 'read'], ['doc', 'Documents', 'studio'], ['agents', 'Agents', 'agents'], ['knowledge', 'Knowledge', 'knowledge'], ['models', 'Models', 'models']];
      const pinned = all.filter((c) => c.pinned);
      w.querySelector('.drawer-body').innerHTML = `<div class="d-head">${searching ? `<input class="input" id="d-q" placeholder="Search chats" value="${esc(query)}">` : '<h2>LOCAL AI</h2>'}<button class="ibtn round" data-d="search" aria-label="${searching ? 'Close search' : 'Search chats'}">${I(searching ? 'close' : 'search')}</button></div>
        ${searching ? '' : secs.map(([ic, t, r]) => `<button class="d-item" data-d="go" data-r="${r}">${I(ic)}<span>${t}</span></button>`).join('')}
        ${pinned.length ? `<h3>Pinned</h3>${pinned.map(row).join('')}` : ''}<h3>${query ? 'Results' : 'Chats'}</h3>${all.filter((c) => !c.pinned).map(row).join('') || '<p class="small faint" style="margin:8px">No chats yet.</p>'}`;
      const qi = w.querySelector('#d-q'); if (qi) { qi.focus(); qi.setSelectionRange(query.length, query.length); qi.addEventListener('input', () => { query = qi.value; paint(); }); }
    };
    w.innerHTML = `<div class="scrim" data-d="close"></div><aside class="drawer" role="dialog" aria-label="Chats and menu"><div class="drawer-body"></div><div class="d-foot"><button class="btn btn-primary" data-d="new">${I('edit', 'icon-sm')}New chat</button><button class="ibtn round" data-d="settings" aria-label="Settings">${I('settings')}</button></div></aside>`;
    w.addEventListener('click', (e) => {
      const t = e.target.closest('[data-d]'); if (!t) return; e.preventDefault(); e.stopPropagation();
      const d = t.getAttribute('data-d'); const id = t.getAttribute('data-id');
      if (d === 'close') close();
      else if (d === 'search') { searching = !searching; query = ''; paint(); }
      else if (d === 'go') { close(); const r = t.getAttribute('data-r'); if (r === 'photo') LA.screens.home.actions['ask-photo'](); else LA.go(r); }
      else if (d === 'open') { close(); LA.go('chat-' + id); }
      else if (d === 'new') { close(); LA.newChat({}); }
      else if (d === 'settings') { close(); LA.go('prefs'); }
      else if (d === 'menu') {
        const c = LA.conv(id); close();
        LA.sheet({ title: esc(c.title), body: `<div class="list"><button class="li" data-action="d-pin">${I('pin')}<div class="grow t">${c.pinned ? 'Unpin' : 'Pin'}</div></button><button class="li" data-action="d-ren">${I('edit')}<div class="grow t">Rename</div></button><button class="li" data-action="d-del" style="color:var(--bad)">${I('trash')}<div class="grow t">Delete</div></button></div>`,
          handlers: {
            'd-pin': () => { c.pinned = !c.pinned; LA.save(); LA.closeSheet(); LA.chatDrawer(currentId, true); },
            'd-ren': () => LA.sheet({ title: 'Rename chat', body: `<input class="input" id="d-ren-in" value="${esc(c.title)}">`, actions: '<button class="btn btn-primary" data-action="d-ren-ok">Save</button>', handlers: { 'd-ren-ok': () => { c.title = document.getElementById('d-ren-in').value.trim() || c.title; LA.save(); LA.closeSheet(); LA.refresh(); LA.chatDrawer(currentId, true); } } }),
            'd-del': () => { LA.S.conversations = LA.S.conversations.filter((x) => x.id !== id); LA.save(); LA.closeSheet(); if (id === currentId) LA.newChat({}); else LA.chatDrawer(currentId, true); }
          } });
      }
    });
    dev.appendChild(w); paint();
  };

  let q = '';
  LA.screens.chats = {
    nav: 'chats',
    render() {
      const list = LA.S.conversations.slice().sort((a, b) => b.updated - a.updated).filter((c) => !q || c.title.toLowerCase().includes(q.toLowerCase()));
      return `${C.topbar({ title: 'Chats', sub: LA.S.conversations.length + ' conversations · stored on device' })}
        <div class="scroll"><div class="body"><div class="search">${I('search')}<input id="chat-search" placeholder="Search conversations" value="${esc(q)}" data-input="chat-q" aria-label="Search conversations"></div>
        <div class="list" id="chat-list">${list.map(LA.convRow).join('') || C.empty('search', 'No matches', 'Try a different word.')}</div><div style="height:64px"></div></div></div>
        <button class="btn btn-primary fab" data-action="new">${I('plus')}New chat</button>`;
    },
    inputs: { 'chat-q': (el) => { q = el.value; const list = LA.S.conversations.slice().sort((a, b) => b.updated - a.updated).filter((c) => !q || c.title.toLowerCase().includes(q.toLowerCase())); document.getElementById('chat-list').innerHTML = list.map(LA.convRow).join('') || C.empty('search', 'No matches', 'Try a different word.'); } },
    actions: { new: () => LA.newChat({}) }
  };
})();
