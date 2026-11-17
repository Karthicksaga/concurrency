/* Store, hash router, event delegation and the global simulation ticker. */
window.LA = window.LA || {};
(function () {
  const KEY = 'localai.prototype.v1';
  LA.screens = LA.screens || {};
  LA.actions = LA.actions || {};
  LA.changes = LA.changes || {};
  LA.tickers = LA.tickers || [];

  /* ---------- store (per-viewer convenience only; resets cleanly if storage is unavailable) ---------- */
  LA.load = function () {
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { saved = null; }
    LA.S = saved && saved.v === 6 ? saved.s : LA.seed();
  };
  let saveTimer;
  LA.save = function () {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => { try { localStorage.setItem(KEY, JSON.stringify({ v: 6, s: LA.S })); } catch (e) { /* storage blocked: keep in memory */ } }, 150);
  };
  LA.reset = function () { LA.S = LA.seed(); LA.S.onboarded = true; LA.wf && LA.wf.reset(); LA.save(); LA.applyLook(); LA.go('home'); LA.toast('Demo data restored'); };
  LA.applyLook = function () { document.documentElement.setAttribute('data-look', LA.S.look === 'light' ? 'light' : 'dark'); };

  /* ---------- routes: only plain #name hashes (letters, digits, hyphens) ---------- */
  const ROUTES = [
    [/^welcome$/, 'welcome'], [/^home$/, 'home'], [/^chat$/, 'chats'], [/^chat-(.+)$/, 'chat'],
    [/^models$/, 'models'], [/^downloads$/, 'downloads'], [/^model-(.+)$/, 'model'],
    [/^agents$/, 'agents'], [/^agent-new$/, 'agentForm'], [/^agent-edit-(.+)$/, 'agentForm'], [/^agent-(.+)$/, 'agent'],
    [/^workflow$/, 'workflow'], [/^knowledge$/, 'knowledge'], [/^kb-new$/, 'kbForm'], [/^kb-(.+)$/, 'kbDetail'], [/^doc-(.+)$/, 'doc'],
    [/^voice$/, 'voice'], [/^voice-(.+)$/, 'voice'], [/^studio$/, 'studio'], [/^tools$/, 'tools'], [/^memory$/, 'memory'], [/^memory-(.+)$/, 'memory'], [/^prefs$/, 'prefs'], [/^monitor$/, 'monitor']
  ];
  LA.resolve = function (h) {
    for (const [re, key] of ROUTES) { const m = h.match(re); if (m) return { key, param: m[1], hash: h }; }
    return { key: 'home', param: null, hash: 'home' };
  };
  LA.go = function (h) { if (location.hash.slice(1) === h) LA.render(); else location.hash = h; };

  const NAV = [['home', 'home', 'Home'], ['chats', 'chat', 'Chat'], ['agents', 'agents', 'Agents'], ['knowledge', 'knowledge', 'Knowledge'], ['models', 'models', 'Models']];
  const NAV_HASH = { home: 'home', chats: 'chat', agents: 'agents', knowledge: 'knowledge', models: 'models' };
  function bottomNav(active) {
    return `<nav class="bottomnav" aria-label="Main">${NAV.map(([k, ic, label]) => `<button class="${k === active ? 'on' : ''}" data-go="${NAV_HASH[k]}" aria-current="${k === active ? 'page' : 'false'}"><span class="pill">${LA.icon(ic)}</span>${label}</button>`).join('')}</nav>`;
  }

  let cur = null;
  LA.cur = () => cur;
  LA.render = function (keepScroll) {
    const view = document.querySelector('.view');
    let h = location.hash.slice(1);
    if (!h) h = LA.S.onboarded ? 'home' : 'welcome';
    const r = LA.resolve(h);
    const scr = LA.screens[r.key];
    const same = cur && cur.hash === r.hash;
    const sc = view.querySelector('.scroll');
    const top = keepScroll && same && sc ? sc.scrollTop : 0;
    if (cur && cur.scr.unmount && !same) cur.scr.unmount();
    if (!same) { LA.closeSheet(); document.querySelectorAll('.toast').forEach((n) => { if (!n.dataset.keep) n.remove(); }); }
    cur = { scr, param: r.param, hash: r.hash, key: r.key };
    // Preserve form state and focus across same-route refreshes (sheets are closed above).
    const keep = {}; let focusId = null; let sel = null;
    if (same) {
      view.querySelectorAll('input[id], textarea[id], select[id]').forEach((el) => { keep[el.id] = el.type === 'checkbox' || el.type === 'radio' ? { c: el.checked } : { v: el.value }; });
      const ae = document.activeElement; if (ae && ae.id && view.contains(ae)) { focusId = ae.id; try { sel = [ae.selectionStart, ae.selectionEnd]; } catch (e) { sel = null; } }
    }
    view.innerHTML = `<div class="${same ? '' : 'fade-in'}" style="display:flex;flex-direction:column;flex:1;min-height:0;position:relative">${scr.render(r.param)}</div>${scr.nav ? bottomNav(scr.nav) : ''}`;
    if (same) {
      Object.keys(keep).forEach((id) => { const el = document.getElementById(id); if (!el || el.hasAttribute('data-fresh')) return; if ('c' in keep[id]) el.checked = keep[id].c; else if (el.type !== 'range' && el.type !== 'file') el.value = keep[id].v; });
      if (focusId) { const el = document.getElementById(focusId); if (el) { el.focus({ preventScroll: true }); if (sel && sel[0] != null) try { el.setSelectionRange(sel[0], sel[1]); } catch (e) { /* not a text field */ } } }
    }
    const nsc = view.querySelector('.scroll');
    if (nsc && top) nsc.scrollTop = top;
    if (scr.mount) scr.mount(view, r.param, same);
    document.title = 'LOCAL AI Prototype';
    document.querySelectorAll('.panel a').forEach((a) => a.classList.toggle('on', a.getAttribute('href') === '#' + r.hash));
  };
  LA.refresh = () => LA.render(true);

  /* ---------- event delegation ---------- */
  function find(map, name) { return map && map[name]; }
  document.addEventListener('click', (ev) => {
    const go = ev.target.closest('[data-go]');
    if (go) { ev.preventDefault(); LA.go(go.getAttribute('data-go')); return; }
    const a = ev.target.closest('[data-action]');
    if (!a) return;
    const name = a.getAttribute('data-action');
    const fn = find(LA.sheetActions, name) || find(cur && cur.scr.actions, name) || find(LA.actions, name);
    if (fn) { ev.preventDefault(); fn(a, ev, cur && cur.param); }
  });
  document.addEventListener('change', (ev) => {
    const el = ev.target.closest('[data-change]');
    if (!el) return;
    const name = el.getAttribute('data-change');
    const fn = find(LA.sheetActions, name) || find(cur && cur.scr.changes, name) || find(LA.changes, name);
    if (fn) fn(el, ev, cur && cur.param);
  });
  document.addEventListener('input', (ev) => {
    const el = ev.target.closest('[data-input]');
    if (!el) return;
    const name = el.getAttribute('data-input');
    const fn = find(LA.sheetActions, name) || find(cur && cur.scr.inputs, name);
    if (fn) fn(el, ev, cur && cur.param);
  });
  document.addEventListener('keydown', (ev) => {
    if (ev.key === 'Escape' && document.querySelector('.sheet-layer')) LA.closeSheet();
  });

  LA.actions.back = (el) => { const to = el.getAttribute('data-to'); LA.go(to || 'home'); };
  LA.actions['close-sheet'] = () => LA.closeSheet();
  LA.actions['copy-code'] = (el) => LA.copy(el.closest('.codeblock').querySelector('pre').innerText);

  /* ---------- global simulation ticker (downloads, indexing, model loading) ---------- */
  setInterval(() => {
    let changed = false;
    LA.tickers.forEach((t) => { if (t()) changed = true; });
    if (changed) LA.save();
    if (cur && cur.scr.tick) cur.scr.tick(changed);
  }, 500);

  /* ---------- reviewer panel ---------- */
  const SCREENS = [
    ['welcome', 'Welcome / Onboarding'], ['home', 'Home dashboard'], ['chat-c-lease', 'AI chat'], ['models', 'Model library'],
    ['model-qwen3-4b', 'Model details'], ['downloads', 'Download progress'], ['agents', 'Agent Studio'], ['agent-new', 'Create agent'],
    ['agent-ag-atlas', 'Agent details'], ['workflow', 'Multi-agent workflow'], ['knowledge', 'Knowledge dashboard'], ['kb-new', 'Create knowledge base'],
    ['doc-doc-lease', 'Document details'], ['tools', 'Tool registry'], ['memory-ag-juno', 'Agent memory'], ['prefs', 'Settings'], ['monitor', 'Performance monitor'],
    ['chat-c-photo', 'Photo chat (vision)'], ['voice', 'Voice conversation'], ['studio', 'Document Studio']
  ];
  LA.SCREENS = SCREENS;
  function panel() {
    return `<aside class="panel" aria-label="Prototype screen index"><h2>Screen index</h2><p>Interactive prototype of the LOCAL AI Android app. Everything here runs on mock data: no model, download, embedding or agent is real.</p>
      <ol>${SCREENS.map(([h, t]) => `<li><a href="#${h}">${t}</a></li>`).join('')}</ol>
      <div class="note">Change the simulated device (4 / 6 / 8 / 12 / 16 GB; 4 and 6 GB use Lite mode) in Settings to see compatibility verdicts update.</div></aside>`;
  }

  /* ---------- launch animation: chip assembles, core lights up, status lines while the app warms up ---------- */
  LA.splash = function (ms) {
    const dev = document.querySelector('.device'); if (!dev || dev.querySelector('.splash')) return;
    const el = document.createElement('div'); el.className = 'splash'; el.setAttribute('aria-hidden', 'true');
    const pins = Array.from({ length: 12 }, (_, i) => `<i style="--i:${i}"></i>`).join('');
    el.innerHTML = `<div class="sp-chip"><div class="sp-pins">${pins}</div><div class="sp-die"><span class="sp-core"></span></div><div class="sp-orbit">${'<b></b>'.repeat(3)}</div></div>
      <div class="sp-word">${'LOCAL'.split('').map((c, i) => `<span style="--i:${i}">${c}</span>`).join('')}<em style="--i:5">·</em>${'AI'.split('').map((c, i) => `<span style="--i:${i + 6}">${c}</span>`).join('')}</div>
      <div class="sp-tag">Private AI, on this phone</div><div class="sp-status"><span>Checking this phone…</span><span>Warming up the model…</span><span>Ready</span></div><div class="sp-bar"><i></i></div>`;
    dev.appendChild(el);
    setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 450); }, ms || 2100);
  };

  window.addEventListener('hashchange', () => LA.render());
  window.addEventListener('DOMContentLoaded', () => {
    LA.load(); LA.applyLook();
    document.getElementById('panel-slot').outerHTML = panel();
    const clock = () => { const d = new Date(); const el = document.getElementById('sb-time'); if (el) el.textContent = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); };
    clock(); setInterval(clock, 30000);
    LA.render();
    // Skipped under automation so tests interact immediately; tests call LA.splash() explicitly.
    if (!navigator.webdriver) LA.splash();
  });
})();
