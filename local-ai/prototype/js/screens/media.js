/* Photos (vision), voice (dictation, read aloud, voice conversation) and Document Studio. All simulated. */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc; const F = LA.fmt;
  LA.screens = LA.screens || {};

  /* ---------- sample photos (inline SVG, no network) ---------- */
  const svg = (body, bg) => 'data:image/svg+xml;utf8,' + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 240"><rect width="320" height="240" fill="${bg}"/>${body}</svg>`);
  LA.PHOTOS = {
    receipt: {
      title: 'Grocery receipt', src: svg('<rect x="92" y="14" width="136" height="212" fill="#fbfaf6" stroke="#ddd"/><text x="160" y="40" font-family="monospace" font-size="13" text-anchor="middle" font-weight="bold" fill="#222">FRESH MART</text><text x="160" y="55" font-family="monospace" font-size="8" text-anchor="middle" fill="#555">14 NOV 2026 18:42</text>' +
        [['Basmati rice 5kg', '649'], ['Toor dal 1kg', '172'], ['Milk 2L', '112'], ['Tomatoes 1kg', '46']].map(([t, p], i) => `<text x="102" y="${82 + i * 18}" font-family="monospace" font-size="9" fill="#333">${t}</text><text x="218" y="${82 + i * 18}" font-family="monospace" font-size="9" text-anchor="end" fill="#333">${p}</text>`).join('') +
        '<line x1="102" y1="160" x2="218" y2="160" stroke="#999" stroke-dasharray="3 2"/><text x="102" y="178" font-family="monospace" font-size="11" font-weight="bold" fill="#111">TOTAL</text><text x="218" y="178" font-family="monospace" font-size="11" font-weight="bold" text-anchor="end" fill="#111">979</text><text x="160" y="204" font-family="monospace" font-size="8" text-anchor="middle" fill="#666">PAID BY UPI · THANK YOU</text>', '#6b7a8f'),
      answer: 'This is a **grocery receipt** from Fresh Mart, dated 14 Nov 2026.\n\n- Basmati rice 5 kg — 649\n- Toor dal 1 kg — 172\n- Milk 2 L — 112\n- Tomatoes 1 kg — 46\n\n**Total: 979** (paid by UPI).'
    },
    plant: {
      title: 'Leaf with spots', src: svg('<path d="M60 200 C 80 80, 220 40, 270 50 C 260 140, 180 210, 60 200 Z" fill="#4c9a4a"/><path d="M60 200 C 120 150, 200 90, 270 50" stroke="#2f6b2e" stroke-width="4" fill="none"/>' +
        [[150, 120], [185, 100], [130, 160], [205, 135], [170, 155]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="9" fill="#a9772b"/><circle cx="${x}" cy="${y}" r="13" fill="none" stroke="#e3c55a" stroke-width="3"/>`).join(''), '#d9e7d3'),
      answer: 'A **green leaf with round brown spots ringed in yellow**. That pattern usually points to a **fungal leaf-spot disease** (for example early blight on tomato or leaf spot on chilli).\n\nWhat usually helps:\n- Remove the spotted leaves and do not compost them\n- Water the soil, not the leaves, in the morning\n- Give the plant more space and air\n\nI can only see the photo, so ask a local nursery if it spreads.'
    },
    bill: {
      title: 'Electricity bill', src: svg('<rect x="40" y="20" width="240" height="200" rx="6" fill="#fff"/><rect x="40" y="20" width="240" height="34" rx="6" fill="#1f5fa8"/><text x="56" y="43" font-family="sans-serif" font-size="13" font-weight="bold" fill="#fff">POWER DISTRIBUTION LTD</text>' +
        [['Consumer no.', '04-118-2291'], ['Billing period', 'Oct 2026'], ['Units used', '286 kWh'], ['Due date', '28 Nov 2026']].map(([k, v], i) => `<text x="56" y="${82 + i * 22}" font-family="sans-serif" font-size="10" fill="#555">${k}</text><text x="264" y="${82 + i * 22}" font-family="sans-serif" font-size="10" text-anchor="end" fill="#111">${v}</text>`).join('') +
        '<rect x="56" y="172" width="208" height="32" rx="4" fill="#fdecea"/><text x="66" y="193" font-family="sans-serif" font-size="12" font-weight="bold" fill="#b3261e">Amount due</text><text x="254" y="193" font-family="sans-serif" font-size="14" font-weight="bold" text-anchor="end" fill="#b3261e">2,184</text>', '#cfd8e3'),
      answer: 'This is your **electricity bill for October 2026**.\n\n- Units used: **286 kWh**\n- Amount due: **2,184**\n- Due date: **28 Nov 2026**\n- Consumer no. 04-118-2291\n\nPay before the due date to avoid a late fee.'
    }
  };
  LA.photoSrc = (p) => (LA.PHOTOS[p] ? LA.PHOTOS[p].src : p);
  LA.photoAnswer = (p, q) => (LA.PHOTOS[p] ? LA.PHOTOS[p].answer : 'I can see your photo. *(Simulated: in the app, the vision model on your phone describes what is actually in it and answers your question' + (q ? ': “' + esc(q) + '”' : '') + '.)*\n\nPhotos are resized to **512 px in lite mode** before the model looks at them, so small text may be hard to read.');
  LA.isVision = (id) => { const m = LA.model(id); return !!(m && m.vision); };

  /** Reads an image file from the user's device, downsized to ≤ 512 px JPEG (kept in this browser only). */
  LA.readPhoto = function (file, cb) {
    const r = new FileReader();
    r.onload = () => {
      const img = new Image();
      img.onload = () => {
        const k = Math.min(1, 512 / Math.max(img.width, img.height));
        const cv = document.createElement('canvas'); cv.width = Math.round(img.width * k); cv.height = Math.round(img.height * k);
        cv.getContext('2d').drawImage(img, 0, 0, cv.width, cv.height);
        cb(cv.toDataURL('image/jpeg', 0.8));
      };
      img.onerror = () => LA.toast('That file is not an image this browser can read.');
      img.src = r.result;
    };
    r.readAsDataURL(file);
  };

  /** Photo picker sheet: camera, gallery or a sample photo. Calls back with a photo key or data URL. */
  LA.photoSheet = function (onPick, withDocs, extra) {
    LA.sheet({
      title: extra ? 'Add to chat' : 'Add a photo',
      body: `<div class="photo-pick"><label class="li tap">${I('camera')}<div class="grow"><div class="t">Take a photo</div><div class="s">Opens the camera app. No camera permission needed.</div></div><input type="file" accept="image/*" capture="environment" data-change="photo-file" hidden></label>
        <label class="li tap">${I('image')}<div class="grow"><div class="t">Choose from gallery</div><div class="s">System photo picker</div></div><input type="file" accept="image/*" data-change="photo-file" hidden></label></div>
        <div class="eyebrow">Sample photos</div><div class="photo-grid">${Object.entries(LA.PHOTOS).map(([k, p]) => `<button data-action="photo-sample" data-k="${k}" aria-label="${esc(p.title)}"><img src="${p.src}" alt=""><span>${esc(p.title)}</span></button>`).join('')}</div>
        ${withDocs ? `<button class="li" data-action="photo-docs">${I('attach')}<div class="grow"><div class="t">Attach a document instead</div><div class="s">PDF, Word, text</div></div></button>` : ''}${extra || ''}`,
      focus: false,
      handlers: {
        'photo-file': (el) => { const f = el.files && el.files[0]; if (f) LA.readPhoto(f, (url) => { LA.closeSheet(); onPick(url); }); },
        'photo-sample': (b) => { LA.closeSheet(); onPick(b.getAttribute('data-k')); },
        'photo-docs': () => { LA.closeSheet(); withDocs(); }
      }
    });
  };

  /* ---------- speech: browser APIs when available, otherwise simulated ---------- */
  LA.speech = {
    speakingId: null,
    // Needs the API and at least one installed voice (headless browsers have none).
    canSpeak: () => { try { return typeof window.SpeechSynthesisUtterance !== 'undefined' && window.speechSynthesis.getVoices().length > 0; } catch (e) { return false; } },
    clean: (md) => md.replace(/```[\s\S]*?```/g, ' (code shown on screen) ').replace(/\[chunk:\d+\]/g, '').replace(/[*_`#>|]/g, '').replace(/\n+/g, '. ').replace(/\s+/g, ' ').trim(),
    speak(text, id, onEnd) {
      this.stop(); this.speakingId = id || 'x';
      if (!this.canSpeak()) { this._t = setTimeout(() => { this.speakingId = null; onEnd && onEnd(); }, Math.min(6000, 600 + text.length * 25)); return; }
      const u = new SpeechSynthesisUtterance(this.clean(text));
      const st = LA.S.settings; if (st.voiceLang) u.lang = st.voiceLang; u.rate = st.speechRate || 1;
      u.onend = u.onerror = () => { if (this.speakingId === (id || 'x')) this.speakingId = null; onEnd && onEnd(); };
      try { window.speechSynthesis.speak(u); } catch (e) { this.speakingId = null; onEnd && onEnd(); }
    },
    stop() { clearTimeout(this._t); this.speakingId = null; try { this.canSpeak() && window.speechSynthesis.cancel(); } catch (e) { /* ignore */ } }
  };

  /* ---------- Document Studio exports ---------- */
  LA.addExport = function (title, fmt, md) {
    const base = (title || 'Document').replace(/[\\/:*?"<>|]/g, ' ').trim().slice(0, 60) || 'Document';
    let name = base + '.' + fmt; let n = 2;
    while (LA.S.exports.some((e) => e.name === name)) name = `${base} (${n++}).${fmt}`;
    const e = { id: LA.uid('ex'), name, fmt, kb: Math.max(6, Math.round(md.length / (fmt === 'pdf' ? 28 : 55))), at: Date.now(), md };
    LA.S.exports.unshift(e); LA.save(); return e;
  };
  const titleOf = (md, fb) => { const m = md.match(/^#\s+(.+)$/m); return m ? m[1].replace(/[*_`]/g, '').trim() : fb; };
  LA.titleOf = titleOf;
  LA.previewExport = function (e) {
    LA.sheet({
      title: esc(e.name),
      body: `<div class="paper ${e.fmt}"><div class="paper-bar">${e.fmt === 'pdf' ? 'PDF · A4 · selectable text' : e.fmt === 'docx' ? 'Word (.docx) · editable' : 'Markdown'}</div><div class="page md">${LA.md(e.md)}</div></div><p class="xs faint" style="margin:0">Preview. In the app, Open uses your PDF or Word viewer.</p>`,
      actions: `<button class="btn btn-ghost" data-action="ex-share">${I('share', 'icon-sm')}Share</button><button class="btn btn-ghost" data-action="ex-save">${I('download', 'icon-sm')}Save to phone</button>`,
      focus: false,
      handlers: { 'ex-share': () => LA.toast('Android share sheet opens with ' + esc(e.name) + ' (simulated)'), 'ex-save': () => LA.toast('Saved to Downloads (simulated)') }
    });
  };
  LA.saveAsSheet = function (md, fallback) {
    LA.sheet({
      title: 'Save as document', body: `<p class="small muted" style="margin:0">Creates a real file on the phone from this answer: headings, lists, tables and code keep their formatting.</p><div class="list"><button class="li" data-action="sv" data-f="pdf">${I('doc')}<div class="grow"><div class="t">PDF</div><div class="s">Selectable text, A4, page numbers</div></div></button><button class="li" data-action="sv" data-f="docx">${I('file')}<div class="grow"><div class="t">Word (.docx)</div><div class="s">Edit in Word, Google Docs or WPS</div></div></button></div>`,
      handlers: {
        sv: (b) => {
          const e = LA.addExport(titleOf(md, fallback), b.getAttribute('data-f'), md); LA.closeSheet();
          LA.sheet({ title: 'Saved ' + e.fmt.toUpperCase(), body: `<p class="muted" style="margin:0">${esc(e.name)} · ${e.kb} KB<br><span class="small faint">Find it any time in Documents › Files.</span></p>`, actions: `<button class="btn btn-ghost" data-action="sv-share">Share</button><button class="btn btn-primary" data-action="sv-open">Open</button>`, handlers: { 'sv-open': () => LA.previewExport(e), 'sv-share': () => { LA.closeSheet(); LA.toast('Android share sheet (simulated)'); } } });
        }
      }
    });
  };

  /* ---------- voice conversation ---------- */
  const VOICE_QA = [
    ['What should I pack for Ooty in December?', 'Pack warm layers: nights in Ooty drop to about **5 degrees** in December. Take a fleece or jacket, a beanie, comfortable walking shoes and a light rain shell. Mornings can be foggy, so carry a torch for early drives.'],
    ['How much is the deposit on my flat?', 'Your lease says the deposit is **two months’ rent**, so about **76,000**. It must be refunded within 30 days after you move out, minus documented deductions.'],
    ['Give me a quick dinner idea with dal and rice.', 'Try **dal khichdi**: cook rice and toor dal together with turmeric and salt, then pour over a tadka of ghee, cumin, garlic and a pinch of asafoetida. Ready in 25 minutes in a pressure cooker.']
  ];
  const PH = { idle: 'Tap to talk', listening: 'Listening…', thinking: 'Thinking…', speaking: 'Speaking · tap to interrupt' };
  let V = null;
  function vReset(id) { if (V) vStop(); V = { conv: id, phase: 'idle', heard: '', reply: '', level: 0, round: 0, handsFree: true, timers: [] }; }
  function vStop() { if (!V) return; V.timers.forEach(clearTimeout); V.timers.forEach(clearInterval); V.timers = []; LA.speech.stop(); }
  function vPaint() {
    const root = document.querySelector('.voice'); if (!root || !V) return;
    root.setAttribute('data-phase', V.phase);
    root.style.setProperty('--lvl', V.phase === 'listening' ? V.level.toFixed(2) : '0');
    root.querySelector('.v-phase').textContent = PH[V.phase];
    root.querySelector('.v-heard').textContent = V.heard ? '“' + V.heard + '”' : '';
    root.querySelector('.v-reply').innerHTML = V.reply ? LA.md(V.reply) : '';
    const busy = V.phase === 'speaking' || V.phase === 'thinking';
    const hint = root.querySelector('.v-hint'); if (hint) hint.textContent = busy ? (LA.S.settings.bargeIn === 'OFF' ? 'Tap the orb to interrupt' : 'Just start talking to interrupt') : '';
    const to = root.querySelector('[data-action=talk-over]'); if (to) to.hidden = !busy;
  }
  function vListen() {
    vStop(); const qa = VOICE_QA[V.round % VOICE_QA.length];
    V.phase = 'listening'; V.heard = ''; V.reply = ''; vPaint();
    const words = qa[0].split(' '); let i = 0;
    const lv = setInterval(() => { V.level = 0.2 + Math.random() * 0.8; vPaint(); }, 120);
    const w = setInterval(() => { i++; V.heard = words.slice(0, i).join(' '); vPaint(); if (i >= words.length) { clearInterval(w); clearInterval(lv); V.level = 0; V.timers.push(setTimeout(() => vAsk(qa), 450)); } }, 230);
    V.timers.push(lv, w);
  }
  function vAsk(qa) {
    V.phase = 'thinking'; vPaint();
    const c = LA.conv(V.conv); if (c) { c.messages.push({ role: 'user', text: qa[0], voice: true }); if (c.title === 'New chat') c.title = 'Voice: ' + qa[0].slice(0, 32); c.updated = Date.now(); LA.save(); }
    V.timers.push(setTimeout(() => vSpeak(qa), 1100));
  }
  function vSpeak(qa) {
    V.phase = 'speaking'; vPaint();
    const words = qa[1].split(' '); let i = 0; let textDone = false; let audioDone = false;
    const finish = () => {
      if (!textDone || !audioDone || !V || V.phase !== 'speaking') return;
      const c = LA.conv(V.conv); if (c) { c.messages.push({ role: 'ai', text: qa[1], model: c.model, tps: +(LA.device().genTps).toFixed(1) }); LA.save(); }
      V.round++;
      if (V.handsFree && V.round % VOICE_QA.length !== 0) V.timers.push(setTimeout(vListen, 700)); else { V.phase = 'idle'; vPaint(); }
    };
    const w = setInterval(() => { i += 2; V.reply = words.slice(0, i).join(' '); vPaint(); if (i >= words.length) { clearInterval(w); textDone = true; finish(); } }, 160);
    V.timers.push(w);
    // The phone starts speaking the first sentence while the rest is still being written.
    LA.speech.speak(qa[1], 'voice', () => { audioDone = true; finish(); });
  }
  LA.screens.voice = {
    render(id) {
      const c = id && LA.conv(id);
      if (!V || (c && V.conv !== c.id)) vReset(c ? c.id : null);
      const st = LA.S.settings; const lang = (LA.VOICE_LANGS.find((l) => l[0] === st.voiceLang) || LA.VOICE_LANGS[0])[1];
      return `${C.topbar({ title: 'Voice', sub: 'On-device speech · ' + esc(lang), back: c ? 'chat-' + c.id : 'home', actions: c ? C.go('chat', 'chat-' + c.id, 'Show as chat') : '' })}
        <div class="voice" data-phase="${V.phase}"><div class="banner info">${I('info')}<span>Simulated conversation. In the app, your words are recognised on the phone and the on-device model answers out loud.</span></div>
        <button class="orb" data-action="orb" aria-label="${PH[V.phase]}"><i></i>${I('mic')}</button>
        <div class="v-phase">${PH[V.phase]}</div><div class="v-heard"></div><div class="v-reply md"></div>
        <div class="v-hint">${V.phase === 'speaking' || V.phase === 'thinking' ? (LA.S.settings.bargeIn === 'OFF' ? 'Tap the orb to interrupt' : 'Just start talking to interrupt') : ''}</div>
        <div class="chips" style="justify-content:center"><button class="chip ${V.handsFree ? 'on' : ''}" data-action="hands">${V.handsFree ? 'Hands-free: on' : 'Hands-free: off'}</button>
        <button class="chip ${LA.S.settings.bargeIn !== 'OFF' ? 'on' : ''}" data-action="barge">Interrupt by voice: ${({ OFF: 'Off', NORMAL: 'Normal', SENSITIVE: 'Sensitive' })[LA.S.settings.bargeIn || 'NORMAL']}</button>
        <button class="chip" data-action="talk-over" ${V.phase === 'speaking' || V.phase === 'thinking' ? '' : 'hidden'}>Simulate: start talking</button></div>
        <span class="xs faint" style="text-align:center">Voice: ${esc((LA.NATURAL.find((v) => v.id === LA.S.settings.ttsVoice) || { name: "phone's voice" }).name)} · change in Settings › Voice</span></div>`;
    },
    mount() { vPaint(); },
    unmount() { vStop(); if (V) { V.phase = 'idle'; } },
    actions: {
      orb: () => {
        if (!V.conv) { const id = LA.uid('c'); LA.S.conversations.unshift({ id, title: 'New chat', model: LA.S.activeModel, agent: null, kbs: [], attachments: [], updated: Date.now(), agentMode: false, messages: [], system: 'You are a helpful assistant running locally on this device. Be concise.' }); V.conv = id; LA.save(); }
        if (V.phase === 'idle') vListen();
        else if (V.phase === 'listening') { vStop(); V.phase = 'idle'; V.level = 0; vPaint(); }
        else if (V.phase === 'thinking') { vStop(); V.phase = 'idle'; vPaint(); LA.toast('Stopped'); }
        else { vStop(); V.round++; vListen(); }
      },
      hands: () => { V.handsFree = !V.handsFree; LA.refresh(); },
      barge: () => { const o = ['NORMAL', 'SENSITIVE', 'OFF']; const s = LA.S.settings; s.bargeIn = o[(o.indexOf(s.bargeIn || 'NORMAL') + 1) % 3]; LA.save(); LA.refresh(); },
      // In the app the microphone hears you (echo-cancelled) and this happens automatically.
      'talk-over': () => { if (LA.S.settings.bargeIn === 'OFF') { LA.toast('Interrupt by voice is off'); return; } vStop(); V.round++; LA.toast('Heard you: stopped and listening'); vListen(); }
    }
  };
  LA.NATURAL = [
    { id: 'amy', name: 'Amy', d: 'American English, warm female · fast', mb: 63 }, { id: 'jenny', name: 'Jenny', d: 'British English, female · fast', mb: 63 },
    { id: 'priyamvada', name: 'Priyamvada', d: 'हिन्दी Hindi, female · fast', mb: 63 }, { id: 'rohan', name: 'Rohan', d: 'हिन्दी Hindi, male · fast', mb: 63 },
    { id: 'lessac', name: 'Lessac HD', d: 'American English, higher quality · slower', mb: 114 }, { id: 'kitten', name: 'Kitten Expressive', d: 'English, 8 expressive voices · slower', mb: 24 }
  ];
  LA.VOICE_LANGS = [['', 'Phone language'], ['en-IN', 'English (India)'], ['en-US', 'English (US)'], ['hi-IN', 'हिन्दी'], ['ta-IN', 'தமிழ்'], ['te-IN', 'తెలుగు'], ['kn-IN', 'ಕನ್ನಡ'], ['ml-IN', 'മലയാളം'], ['bn-IN', 'বাংলা'], ['es-ES', 'Español'], ['fr-FR', 'Français'], ['de-DE', 'Deutsch']];

  /* ---------- Document Studio ---------- */
  const KINDS = [['report', 'Report'], ['letter', 'Letter'], ['email', 'Email'], ['minutes', 'Meeting notes'], ['resume', 'Resume'], ['essay', 'Essay'], ['proposal', 'Proposal']];
  const ACTIONS = [['none', 'Convert only'], ['tidy', 'Fix grammar & tidy'], ['summarize', 'Summarize'], ['translate', 'Translate'], ['formal', 'Make it formal'], ['simplify', 'Simplify']];
  const SOURCES = [
    { id: 's-offer', name: 'Offer letter.docx', fmt: 'docx', pages: 2, md: '# Offer of employment\n\nDear Karthik,\n\nWe are pleased to offer you the position of **Senior Android Engineer** at Northwind Labs, starting **1 January 2027**.\n\n## Compensation\n\n| Component | Annual |\n| --- | --- |\n| Base salary | 32,00,000 |\n| Joining bonus | 2,00,000 |\n\n## Terms\n- Notice period: 60 days\n- Location: Bengaluru (hybrid, 3 days in office)\n\nPlease sign and return by 30 November 2026.' },
    { id: 's-lease', name: 'Lease agreement — Flat 4B.pdf', fmt: 'pdf', pages: 11, md: '# Lease agreement — Flat 4B\n\n## 7. Deposit\nThe Tenant shall pay a refundable security deposit equal to two months’ rent, returned within 30 days of the end of the tenancy, less documented deductions.\n\n## 9. Notice\nEither party may terminate this agreement by giving not less than sixty (60) days’ written notice.\n\n## 11. Maintenance\nMinor repairs under 2,000 are the responsibility of the Tenant.' },
    { id: 's-notes', name: 'Project notes.md', fmt: 'md', pages: 1, md: '# Project notes\n\n- lite mode works on 4gb phone but slow first answer\n- need ocr for scaned pdf\n- voice: use on device recogniser, fall back to system one' }
  ];
  const T = { tab: 'write', kind: 'letter', req: '', len: 'medium', lang: '', draft: '', busy: false, src: null, target: 'pdf', action: 'none', tlang: 'Tamil', part: 0, parts: 0, preview: '', timer: null };
  function draftFor(kind, req) {
    const r = req.trim() || 'the topic you described';
    const today = '17 November 2026';
    const docs = {
      letter: `# Leave request\n\n**Date:** ${today}\n\nDear [Manager name],\n\nI am writing to request leave regarding ${r}. I have planned my work so that nothing is blocked while I am away.\n\n## Handover\n- Ongoing tasks: handed to [colleague]\n- Urgent contact: reachable by phone\n\nThank you for considering my request.\n\nYours sincerely,  \n[Your name]`,
      report: `# Report: ${r}\n\n## Executive summary\nThis report covers ${r}, the main findings and the recommended next steps.\n\n## Findings\n- Finding one with supporting numbers\n- Finding two and its impact\n\n| Area | Status | Owner |\n| --- | --- | --- |\n| Planning | On track | [name] |\n| Budget | Review | [name] |\n\n## Recommendation\nProceed with the plan and review progress in two weeks.`,
      email: `# Email\n\n**Subject:** ${r}\n\nHi [name],\n\nQuick note about ${r}. Could you confirm by Friday whether this works for you?\n\nThanks,  \n[Your name]`,
      minutes: `# Meeting notes — ${r}\n\n**Date:** ${today}  \n**Attendees:** [names]\n\n## Decisions\n- Decision one\n- Decision two\n\n## Action items\n\n| Owner | Action | Due |\n| --- | --- | --- |\n| [name] | Follow up on ${r} | [date] |`,
      resume: `# [Your name]\n\n[City] · [phone] · [email]\n\n## Summary\nProfessional focused on ${r}.\n\n## Experience\n- **[Role], [Company]** (2022–present): key achievement with a number\n\n## Skills\n- Skill one · Skill two · Skill three`,
      essay: `# ${r.charAt(0).toUpperCase() + r.slice(1)}\n\n${r.charAt(0).toUpperCase() + r.slice(1)} matters more today than ever. This essay looks at why, what is changing, and what we can do.\n\n## Why it matters\nA short argument with an example.\n\n## Conclusion\nA closing thought that ties the points together.`,
      proposal: `# Proposal: ${r}\n\n## Background\nWhy ${r} is needed now.\n\n## Plan\n1. Discovery (2 weeks)\n2. Build (6 weeks)\n3. Rollout (2 weeks)\n\n## Budget\n\n| Item | Cost |\n| --- | --- |\n| Team | [amount] |\n| Tools | [amount] |`
    };
    return docs[kind];
  }
  function transformed(src, action, lang) {
    if (action === 'summarize') return `# Summary — ${titleOf(src.md, src.name)}\n\n${src.id === 's-offer' ? 'A job offer for **Senior Android Engineer** at Northwind Labs starting 1 Jan 2027.\n\n## Key points\n- Base salary **32,00,000** + joining bonus **2,00,000**\n- 60-day notice period; hybrid in Bengaluru\n- Sign and return by **30 Nov 2026**' : src.id === 's-lease' ? 'The lease for Flat 4B.\n\n## Key points\n- Deposit: **two months’ rent**, refunded within 30 days\n- Notice: **60 days** in writing\n- Tenant pays minor repairs under 2,000' : 'Notes on the LOCAL AI project.\n\n## Key points\n- Lite mode works on 4 GB but the first answer is slow\n- OCR is needed for scanned PDFs\n- Voice uses the on-device recognizer first'}`;
    if (action === 'translate') return src.md.replace(/^# (.+)$/m, `# $1 (${lang || 'translated'})`) + `\n\n> Simulated translation into ${esc(lang || 'the chosen language')}. In the app the model translates every heading, paragraph and table cell.`;
    if (action === 'tidy') return src.id === 's-notes' ? '# Project notes\n\n- Lite mode works on 4 GB phones, but the first answer is slow.\n- We need OCR for scanned PDFs.\n- Voice: use the on-device recognizer and fall back to the system one.' : src.md;
    if (action === 'formal') return src.md.replace('Dear Karthik', 'Dear Mr. Karthik').replace('# Project notes', '# Project status notes');
    if (action === 'simplify') return src.md.replace('not less than sixty (60) days’ written notice', '60 days’ notice in writing').replace('less documented deductions', 'minus any costs they can prove');
    return src.md;
  }
  function studioRun(fn, steps, done) {
    clearInterval(T.timer); T.busy = true; let i = 0;
    T.timer = setInterval(() => { i++; fn(i); if (i >= steps) { clearInterval(T.timer); T.timer = null; T.busy = false; done(); } if (LA.cur().key === 'studio') LA.refresh(); }, 140);
  }
  LA.screens.studio = {
    render() {
      const tabs = C.seg('Studio', [['write', 'Write'], ['convert', 'Convert'], ['files', `Files (${LA.S.exports.length})`]], T.tab, 'st-tab', true);
      let body = '';
      if (T.tab === 'write') {
        body = `<div class="card col"><b>What should I write?</b><div class="chips">${KINDS.map(([k, l]) => `<button class="chip ${T.kind === k ? 'on' : ''}" data-action="st-kind" data-v="${k}">${l}</button>`).join('')}</div>
          <div class="field"><label for="st-req">Describe it</label><textarea class="textarea" id="st-req" placeholder="e.g. Leave for 3 days from 20 Nov for a family function" data-input="st-req">${esc(T.req)}</textarea></div>
          <div class="row between"><span class="small muted">Length</span>${C.seg('Length', [['short', 'Short'], ['medium', 'Medium'], ['long', 'Long']], T.len, 'st-len')}</div>
          ${T.busy ? `<button class="btn btn-ghost" data-action="st-stop">${I('stop', 'icon-sm')}Stop</button>` : `<button class="btn btn-primary" data-action="st-write">${I('sparkle', 'icon-sm')}${T.draft ? 'Write again' : 'Write with AI'}</button>`}
          <span class="xs faint">Uses the loaded model on the phone. Check names, dates and numbers before you send it.</span></div>
          ${T.draft ? `<div class="card"><div class="row between"><b>Preview</b>${C.sample('Simulated')}</div><div class="md doc-preview">${LA.md(T.draft)}${T.busy ? '<span class="cursor"></span>' : ''}</div></div>
          ${T.busy ? '' : `<div class="row" style="gap:10px"><button class="btn btn-primary grow" data-action="st-save" data-f="pdf">${I('doc', 'icon-sm')}Save PDF</button><button class="btn btn-tonal grow" data-action="st-save" data-f="docx">${I('file', 'icon-sm')}Save Word</button></div>`}` : ''}`;
      } else if (T.tab === 'convert') {
        const s = T.src && SOURCES.find((x) => x.id === T.src);
        body = `<div class="card col"><b>1 · Choose a file</b><span class="small muted">Word (.docx), PDF with text, Markdown or text.</span>
          ${s ? `<div class="li" style="padding:0">${I(s.fmt === 'pdf' ? 'doc' : 'file')}<div class="grow"><div class="t">${esc(s.name)}</div><div class="s">${s.pages} page${s.pages > 1 ? 's' : ''} · ${s.fmt.toUpperCase()}</div></div><button class="link" data-action="st-clear">Change</button></div>`
            : `<div class="list">${SOURCES.map((x) => `<button class="li" data-action="st-src" data-v="${x.id}">${I(x.fmt === 'pdf' ? 'doc' : 'file')}<div class="grow"><div class="t">${esc(x.name)}</div><div class="s">${x.pages} page${x.pages > 1 ? 's' : ''} · sample file</div></div></button>`).join('')}</div>`}</div>
          ${s ? `<div class="card col"><b>2 · Convert to</b><div class="chips">${[['pdf', 'PDF'], ['docx', 'Word'], ['md', 'Markdown']].map(([k, l]) => `<button class="chip ${T.target === k ? 'on' : ''}" data-action="st-target" data-v="${k}">${l}</button>`).join('')}</div>
            <b>3 · AI on the way (optional)</b><div class="chips">${ACTIONS.map(([k, l]) => `<button class="chip ${T.action === k ? 'on' : ''}" data-action="st-action" data-v="${k}">${l}</button>`).join('')}</div>
            ${T.action === 'translate' ? `<div class="field"><label for="st-tlang">Translate into</label><input class="input" id="st-tlang" value="${esc(T.tlang)}" data-input="st-tlang"></div>` : ''}
            ${T.action !== 'none' ? '<span class="xs faint">Long files are processed part by part (about a page at a time in lite mode).</span>' : ''}
            ${T.busy ? `<button class="btn btn-ghost" data-action="st-stop">${I('stop', 'icon-sm')}Stop</button>` : `<button class="btn btn-primary" data-action="st-convert">${T.action === 'none' ? 'Convert to ' : esc(ACTIONS.find((a) => a[0] === T.action)[1]) + ' → '}${T.target === 'docx' ? 'Word' : T.target.toUpperCase()}</button>`}</div>
            ${T.busy ? `<div class="card col"><span class="small muted">Part ${T.part} of ${T.parts}</span>${C.progress(T.part / T.parts)}</div>` : ''}
            <div class="card"><b>${T.preview ? 'Live preview' : 'Original'}</b><div class="md doc-preview">${LA.md(T.preview || s.md)}</div></div>` : ''}`;
      } else {
        body = LA.S.exports.length ? `<div class="list">${LA.S.exports.map((e) => `<div class="li"><button class="grow row tap-plain" data-action="ex-open" data-id="${e.id}" style="gap:12px;min-width:0">${I(e.fmt === 'pdf' ? 'doc' : 'file')}<div class="grow" style="min-width:0;text-align:left"><div class="t ellipsis">${esc(e.name)}</div><div class="s">${e.kb} KB · ${F.ago(e.at)}</div></div></button>${C.ib('share', 'ex-share', 'Share', `data-id="${e.id}"`)}${C.ib('trash', 'ex-del', 'Delete', `data-id="${e.id}"`)}</div>`).join('')}</div>`
          : C.empty('doc', 'No documents yet', 'Documents you write, convert or save from chat appear here.');
      }
      return `${C.topbar({ title: 'Document Studio', sub: 'PDF & Word, written and converted on the phone', back: 'home' })}
        <div class="scroll"><div class="body">${tabs}${body}</div></div>`;
    },
    unmount() { clearInterval(T.timer); T.timer = null; T.busy = false; },
    inputs: { 'st-req': (el) => { T.req = el.value; }, 'st-tlang': (el) => { T.tlang = el.value; } },
    actions: {
      'st-tab': (el) => { T.tab = el.getAttribute('data-v'); LA.refresh(); },
      'st-kind': (el) => { T.kind = el.getAttribute('data-v'); LA.refresh(); },
      'st-len': (el) => { T.len = el.getAttribute('data-v'); LA.refresh(); },
      'st-write': () => {
        T.req = (document.getElementById('st-req') || {}).value || T.req;
        if (!T.req.trim()) { LA.toast('Describe what to write'); document.getElementById('st-req').focus(); return; }
        const full = draftFor(T.kind, T.req); const words = full.split(/(?<=\s)/); const n = Math.ceil(words.length / 6);
        T.draft = '';
        studioRun((i) => { T.draft = words.slice(0, i * 6).join(''); }, n, () => { T.draft = full; LA.refresh(); });
        LA.refresh();
      },
      'st-stop': () => { clearInterval(T.timer); T.timer = null; T.busy = false; LA.refresh(); LA.toast('Stopped'); },
      'st-save': (el) => { const f = el.getAttribute('data-f'); const e = LA.addExport(titleOf(T.draft, KINDS.find((k) => k[0] === T.kind)[1]), f, T.draft); LA.toast(esc(e.name) + ' saved · see Files'); LA.refresh(); },
      'st-src': (el) => { T.src = el.getAttribute('data-v'); T.preview = ''; const s = SOURCES.find((x) => x.id === T.src); T.target = s.fmt === 'pdf' ? 'docx' : 'pdf'; LA.refresh(); },
      'st-clear': () => { T.src = null; T.preview = ''; LA.refresh(); },
      'st-target': (el) => { T.target = el.getAttribute('data-v'); LA.refresh(); },
      'st-action': (el) => { T.action = el.getAttribute('data-v'); LA.refresh(); },
      'st-convert': () => {
        const s = SOURCES.find((x) => x.id === T.src); const out = transformed(s, T.action, T.tlang);
        T.parts = T.action === 'none' ? 1 : Math.max(2, Math.ceil(s.pages / (LA.device().lite ? 1 : 3))); T.part = 0; T.preview = '';
        const lines = out.split('\n'); const per = Math.ceil(lines.length / 8);
        studioRun((i) => { T.part = Math.min(T.parts, Math.ceil((i / 8) * T.parts)); T.preview = lines.slice(0, i * per).join('\n'); }, 8, () => {
          const suffix = T.action === 'none' ? '' : T.action === 'summarize' ? '' : T.action === 'translate' ? ' – ' + T.tlang : ' – edited';
          const title = (T.action === 'summarize' ? titleOf(out, s.name) : s.name.replace(/\.[a-z]+$/, '')) + suffix;
          const e = LA.addExport(title, T.target, out); T.preview = out;
          LA.toast(esc(e.name) + ' ready'); LA.refresh(); LA.previewExport(e);
        });
        LA.refresh();
      },
      'ex-open': (el) => { const e = LA.S.exports.find((x) => x.id === el.getAttribute('data-id')); if (e) LA.previewExport(e); },
      'ex-share': () => LA.toast('Android share sheet (simulated)'),
      'ex-del': (el) => { const id = el.getAttribute('data-id'); const e = LA.S.exports.find((x) => x.id === id); LA.confirm({ title: 'Delete document?', body: esc(e.name) + ' is removed from the phone.', ok: 'Delete', danger: true, onOk: () => { LA.S.exports = LA.S.exports.filter((x) => x.id !== id); LA.save(); LA.refresh(); LA.toast('Deleted'); } }); }
    }
  };
})();
