/* Agent Studio, create/edit agent, agent details and agent memory. */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc; const F = LA.fmt;
  LA.screens = LA.screens || {};

  function agentCard(a) {
    const m = a.model ? LA.model(a.model) : null;
    const modelWarn = m && m.state !== 'installed';
    return `<button class="card tap agent-card" data-go="agent-${a.id}">
      <div class="row" style="align-items:flex-start">${C.avatar(a)}<div class="grow"><div class="row between"><b style="font:600 16px/1.3 var(--font-display)">${esc(a.name)}</b>${C.status(a.last.status)}</div><div class="small muted">${esc(a.role)} · ${a.runs} runs${a.last.at ? ' · ' + F.ago(a.last.at) : ''}</div></div></div>
      <div class="small" style="color:var(--text-2)">${esc(a.desc)}</div>
      <div class="meta">${C.modelTag(a.model)}${modelWarn ? `<span class="tag" style="background:var(--warn-soft);color:var(--warn)">${I('alert', 'icon-sm')}Not installed</span>` : ''}${a.kbs.map((k) => `<span class="tag kb">${I('knowledge', 'icon-sm')}${esc(LA.kb(k) ? LA.kb(k).name : k)}</span>`).join('')}</div>
      <div class="meta">${a.tools.map((t) => `<span class="tag">${I(LA.tool(t).icon, 'icon-sm')}${esc(LA.tool(t).name)}</span>`).join('')}${a.memory.enabled ? `<span class="tag agent">${I('memory', 'icon-sm')}Memory</span>` : ''}</div></button>`;
  }

  let filter = 'all';
  LA.screens.agents = {
    nav: 'agents',
    render() {
      const list = LA.S.agents.filter((a) => filter === 'all' || (filter === 'failed' ? a.last.status === 'failed' : a.memory.enabled));
      return `${C.topbar({ title: 'Agent Studio', sub: LA.S.agents.length + ' agents · share one loaded model', actions: C.go('tool', 'tools', 'Tool registry') + C.go('memory', 'memory', 'Agent memory') })}
      <div class="scroll"><div class="body">
        <button class="card tap hero" data-go="workflow" style="display:flex;gap:14px;align-items:center"><span class="avatar" style="background:var(--hue-agent-soft);color:var(--hue-agent)">${I('workflow')}</span><div class="grow"><b style="font:600 15px var(--font-display)">Supervisor workflow</b><div class="small muted">Plan → delegate → review → aggregate. Watch agents pass work to each other.</div></div>${I('chevron')}</button>
        <div class="chips">${[['all', 'All'], ['memory', 'With memory'], ['failed', 'Needs attention']].map(([k, t]) => `<button class="chip ${filter === k ? 'on' : ''}" data-action="f" data-v="${k}">${t}</button>`).join('')}</div>
        <div class="col" style="gap:12px">${list.map(agentCard).join('') || C.empty('agents', 'Nothing here', 'No agents match this filter.')}</div>
        <div style="height:64px"></div></div></div>
      <button class="btn btn-primary fab" data-go="agent-new">${I('plus')}New agent</button>`;
    },
    actions: { f: (el) => { filter = el.getAttribute('data-v'); LA.refresh(); } }
  };

  /* ---------- create / edit ---------- */
  let d = null; let errs = {};
  const COLORS = ['model', 'agent', 'knowledge', 'accent', 'warn', 'ok', 'bad'];
  function fromTemplate(t) {
    return { id: null, template: t.id, name: '', role: t.name, desc: t.desc, color: t.color, icon: t.icon, model: null, tools: t.tools.slice(), kbs: [], memory: { enabled: t.memory, autoSave: false }, exec: { maxSteps: 8, maxTools: 6, timeout: 120, temp: 0.4 }, prompt: t.prompt };
  }
  LA.screens.agentForm = {
    render(id) {
      if (!d || d._for !== (id || 'new')) {
        const a = id ? LA.agent(id) : null;
        d = a ? JSON.parse(JSON.stringify(a)) : fromTemplate(LA.templates[0]); d._for = id || 'new'; d._step = a ? 1 : 0; errs = {};
      }
      const editing = !!id;
      if (d._step === 0) {
        return `${C.topbar({ title: 'New agent', sub: 'Step 1 of 2 · choose a starting point', back: 'agents' })}
        <div class="scroll"><div class="body"><div class="tpl-grid">${LA.templates.map((t) => { const h = LA.hue(t.color); return `<button class="tpl ${d.template === t.id ? 'on' : ''}" data-action="tpl" data-id="${t.id}"><span class="avatar sm" style="background:${h[0]};color:${h[1]}">${I(t.icon)}</span><span class="t">${t.name}</span><span class="s">${t.desc}</span></button>`; }).join('')}</div>
        <p class="small faint" style="margin:0">Templates prefill the prompt, tools and memory settings. You can change everything on the next step.</p></div></div>
        <div class="composer" style="flex-direction:row"><button class="btn btn-primary btn-block" data-action="next">Continue with ${esc((LA.templates.find((t) => t.id === d.template) || {}).name || '')}</button></div>`;
      }
      const gens = LA.S.models.filter((m) => m.purpose === 'gen');
      const kbs = LA.S.kbs.filter((k) => k.scope === 'SHARED' || k.owner === d.id);
      const h = LA.hue(d.color);
      return `${C.topbar({ title: editing ? 'Edit ' + esc(d.name) : 'New agent', sub: editing ? 'Changes apply to the next run' : 'Step 2 of 2 · configure', back: editing ? 'agent-' + id : 'agents' })}
      <div class="scroll"><div class="body">
        <div class="row" style="gap:14px"><span class="avatar lg" style="background:${h[0]};color:${h[1]}">${I(d.icon)}</span><div class="chips">${COLORS.map((c) => { const x = LA.hue(c); return `<button class="chip sm" aria-label="Colour ${c}" data-action="color" data-v="${c}" style="width:28px;padding:0;justify-content:center;background:${x[0]};border-color:${d.color === c ? x[1] : 'transparent'}"><i style="width:10px;height:10px;border-radius:50%;background:${x[1]}"></i></button>`; }).join('')}</div></div>
        <div class="field"><label for="a-name">Name</label><input class="input" id="a-name" maxlength="32" placeholder="e.g. Atlas" value="${esc(d.name)}" data-input="name" data-fresh>${errs.name ? `<span class="err">${errs.name}</span>` : '<span class="hint">Shown in chats and workflows.</span>'}</div>
        <div class="field"><label for="a-role">Role</label><input class="input" id="a-role" maxlength="40" value="${esc(d.role)}" data-input="role" data-fresh></div>
        <div class="field"><label for="a-desc">Description</label><input class="input" id="a-desc" maxlength="120" value="${esc(d.desc)}" data-input="desc" data-fresh></div>
        <div class="field"><label for="a-prompt">System prompt</label><textarea class="textarea" id="a-prompt" rows="5" data-input="prompt" data-fresh>${esc(d.prompt)}</textarea><span class="hint">${d.prompt.length} characters · cached on first run to speed up later runs.</span>${errs.prompt ? `<span class="err">${errs.prompt}</span>` : ''}</div>
        <div class="field"><label for="a-model">Model</label><select class="select" id="a-model" data-change="model" data-fresh><option value="">Use the active model (${esc(LA.model(LA.S.activeModel).name)})</option>${gens.map((m) => `<option value="${m.id}" ${d.model === m.id ? 'selected' : ''}>${esc(m.name)} · ${m.quant}${m.state === 'installed' ? '' : ' · not installed'}</option>`).join('')}</select><span class="hint">Agents share one loaded model. A different model means a switch (~3–8 s) when this agent runs.</span></div>
        <div class="field"><span class="label">Tools</span><div class="col" style="gap:8px">${LA.S.tools.map((t) => { const on = d.tools.includes(t.id); return `<label class="check ${on ? 'on' : ''}"><input type="checkbox" value="${t.id}" ${on ? 'checked' : ''} data-change="tool"><span class="avatar sm" style="background:var(--surface-3);color:var(--text-2)">${I(t.icon)}</span><div class="grow"><b class="small">${esc(t.name)}</b><div class="xs faint">${esc(t.desc)}</div></div>${t.permission === 'ASK' ? '<span class="tag">Asks</span>' : t.permission === 'OFF' ? '<span class="tag">Off</span>' : ''}</label>`; }).join('')}</div></div>
        <div class="field"><span class="label">Knowledge bases</span><div class="col" style="gap:8px">${kbs.map((k) => { const on = d.kbs.includes(k.id); return `<label class="check ${on ? 'on' : ''}"><input type="checkbox" value="${k.id}" ${on ? 'checked' : ''} data-change="kb">${C.kbIcon(k, 'sm')}<div class="grow"><b class="small">${esc(k.name)}</b><div class="xs faint">${LA.kbStats(k).docs} documents</div></div></label>`; }).join('')}</div></div>
        <div class="card col" style="gap:14px"><b>Memory</b>
          <div class="row between"><div><div class="small" style="font-weight:600">Remember across conversations</div><div class="xs faint">Facts and preferences this agent learns</div></div>${C.sw('a-mem', d.memory.enabled, 'mem', 'Agent memory')}</div>
          <div class="row between"><div><div class="small" style="font-weight:600">Save without asking</div><div class="xs faint">Off: new memories wait in the inbox for approval</div></div>${C.sw('a-auto', d.memory.autoSave, 'auto', 'Auto-save memories', !d.memory.enabled)}</div></div>
        <div class="card col" style="gap:14px"><b>Execution limits</b>
          ${[['maxSteps', 'Max steps', 1, 16, 1, ''], ['maxTools', 'Max tool calls', 0, 12, 1, ''], ['timeout', 'Time limit', 30, 600, 30, ' s'], ['temp', 'Temperature', 0, 1.5, 0.05, '']].map(([k, l, mn, mx, st, u]) => `<div class="field"><div class="range-row"><label for="x-${k}">${l}</label><output id="o-${k}">${d.exec[k]}${u}</output></div><input type="range" id="x-${k}" min="${mn}" max="${mx}" step="${st}" value="${d.exec[k]}" data-input="exec" data-k="${k}" data-u="${u}"></div>`).join('')}
          <span class="hint">Runs stop at whichever limit is reached first and return partial results.</span></div>
      </div></div>
      <div class="composer" style="flex-direction:row;gap:10px">${editing ? '' : `<button class="btn btn-ghost" data-action="prev">Back</button>`}<button class="btn btn-primary grow" data-action="save">${editing ? 'Save changes' : 'Create agent'}</button></div>`;
    },
    unmount() { d = null; },
    inputs: {
      name: (el) => { d.name = el.value; }, role: (el) => { d.role = el.value; }, desc: (el) => { d.desc = el.value; }, prompt: (el) => { d.prompt = el.value; },
      exec: (el) => { const k = el.getAttribute('data-k'); d.exec[k] = +el.value; document.getElementById('o-' + k).textContent = d.exec[k] + el.getAttribute('data-u'); }
    },
    changes: {
      model: (el) => { d.model = el.value || null; },
      tool: (el) => { d.tools = el.checked ? [...d.tools, el.value] : d.tools.filter((x) => x !== el.value); el.closest('.check').classList.toggle('on', el.checked); },
      kb: (el) => { d.kbs = el.checked ? [...d.kbs, el.value] : d.kbs.filter((x) => x !== el.value); el.closest('.check').classList.toggle('on', el.checked); },
      mem: (el) => { d.memory.enabled = el.checked; if (!el.checked) d.memory.autoSave = false; LA.refresh(); },
      auto: (el) => { d.memory.autoSave = el.checked; }
    },
    actions: {
      tpl: (el) => { const t = LA.templates.find((x) => x.id === el.getAttribute('data-id')); d = Object.assign(fromTemplate(t), { _for: 'new', _step: 0 }); LA.refresh(); },
      next: () => { d._step = 1; LA.render(); },
      prev: () => { d._step = 0; LA.render(); },
      color: (el) => { d.color = el.getAttribute('data-v'); LA.refresh(); },
      save: () => {
        errs = {};
        const name = d.name.trim();
        if (!name) errs.name = 'Give the agent a name.';
        else if (LA.S.agents.some((a) => a.name.toLowerCase() === name.toLowerCase() && a.id !== d.id)) errs.name = 'Another agent already uses this name.';
        if (d.prompt.trim().length < 20) errs.prompt = 'Write at least a sentence so the agent knows its job.';
        if (Object.keys(errs).length) { LA.refresh(); const f = document.querySelector('.err'); if (f) f.scrollIntoView({ block: 'center' }); return; }
        const out = JSON.parse(JSON.stringify(d)); delete out._for; delete out._step; out.name = name;
        if (out.id) { const i = LA.S.agents.findIndex((a) => a.id === out.id); LA.S.agents[i] = Object.assign(LA.S.agents[i], out); }
        else { out.id = LA.uid('ag'); out.last = { status: 'never', at: null }; out.runs = 0; LA.S.agents.unshift(out); }
        LA.save(); const id = out.id; d = null; LA.go('agent-' + id); LA.toast(name + ' saved');
      }
    }
  };

  /* ---------- details ---------- */
  LA.screens.agent = {
    render(id) {
      const a = LA.agent(id);
      if (!a) return C.topbar({ title: 'Agent not found', back: 'agents' }) + '<div class="scroll"><div class="body">' + C.empty('agents', 'This agent was deleted', '') + '</div></div>';
      const m = a.model ? LA.model(a.model) : LA.model(LA.S.activeModel);
      const mems = LA.S.memories.filter((x) => x.agent === a.id);
      const runs = a.runs ? [[a.last.status, a.last.at, a.last.error || 'Answered with 3 citations · 4 steps · 1.9k tokens'], ['completed', a.last.at - 86400000 * 2, 'Answered · 2 steps · 0.8k tokens'], ['completed', a.last.at - 86400000 * 5, 'Answered · 5 steps · 2.6k tokens']] : [];
      return `${C.topbar({ title: '', back: 'agents', actions: C.ib('edit', 'edit', 'Edit agent') + C.ib('more', 'more', 'More') })}
      <div class="scroll"><div class="body">
        <div class="row" style="gap:14px">${C.avatar(a, 'lg')}<div class="grow"><div style="font:600 22px/1.2 var(--font-display)">${esc(a.name)}</div><div class="muted small">${esc(a.role)}</div></div>${C.status(a.last.status)}</div>
        <p class="muted" style="margin:0">${esc(a.desc)}</p>
        ${a.last.status === 'failed' ? `<div class="banner bad">${I('alert')}<span><b>Last run failed.</b> ${esc(a.last.error)}. Raise the tool timeout or narrow the page range.</span></div>` : ''}
        <div class="stats"><div class="stat"><div class="k">Runs</div><div class="v">${a.runs}</div></div><div class="stat"><div class="k">Memories</div><div class="v">${mems.filter((x) => x.status === 'ACTIVE').length}</div></div><div class="stat"><div class="k">Max steps</div><div class="v">${a.exec.maxSteps}</div></div></div>
        <div class="card"><dl class="kv"><dt>Model</dt><dd>${esc(m.name)}${a.model ? '' : ' (active)'}${m.state !== 'installed' ? ' · <span style="color:var(--warn)">not installed</span>' : ''}</dd><dt>Temperature</dt><dd>${a.exec.temp}</dd><dt>Tool calls</dt><dd>≤ ${a.exec.maxTools} per run</dd><dt>Time limit</dt><dd>${a.exec.timeout} s</dd><dt>Memory</dt><dd>${a.memory.enabled ? (a.memory.autoSave ? 'On · auto-save' : 'On · needs approval') : 'Off'}</dd></dl></div>
        ${C.sectionH('Tools')}<div class="list">${a.tools.map((t) => { const x = LA.tool(t); return `<div class="li"><span class="avatar sm" style="background:var(--surface-3);color:var(--text-2)">${I(x.icon)}</span><div class="grow"><div class="t">${esc(x.name)}</div><div class="s">${{ ALLOWED: 'Allowed', ASK: 'Asks every time', OFF: 'Turned off globally' }[x.permission]}</div></div></div>`; }).join('') || '<div class="li muted small">No tools · chat only</div>'}</div>
        ${C.sectionH('Knowledge')}<div class="list">${a.kbs.map((k) => LA.kbRow(LA.kb(k))).join('') || '<div class="li muted small">No knowledge bases</div>'}</div>
        ${C.sectionH('Memory', 'Manage', 'memory-' + a.id)}<div class="list">${mems.filter((x) => x.status === 'ACTIVE').slice(0, 3).map((x) => `<div class="li mem-item"><span class="mem-kind ${x.kind}">${x.kind.slice(0, 4)}</span><div class="grow small">${esc(x.content)}</div></div>`).join('') || `<div class="li muted small">${a.memory.enabled ? 'Nothing remembered yet' : 'Memory is off for this agent'}</div>`}</div>
        ${C.sectionH('System prompt')}<div class="card small muted" style="white-space:pre-wrap">${esc(a.prompt)}</div>
        ${C.sectionH('Recent runs')}<div class="list">${runs.map(([s, t, x]) => `<div class="li">${C.status(s)}<div class="grow"><div class="s">${esc(x)}</div></div><span class="xs faint">${F.ago(t)}</span></div>`).join('') || '<div class="li muted small">Not run yet</div>'}</div>
      </div></div>
      <div class="composer" style="flex-direction:row;gap:10px"><button class="btn btn-outline grow" data-go="workflow">${I('workflow', 'icon-sm')}Use in workflow</button><button class="btn btn-primary grow" data-action="chat">${I('chat', 'icon-sm')}Chat</button></div>`;
    },
    actions: {
      edit: (el, ev, id) => LA.go('agent-edit-' + id),
      chat: (el, ev, id) => { const a = LA.agent(id); LA.newChat({ agent: id, kbs: a.kbs.slice(), title: 'Chat with ' + a.name }); },
      more: (el, ev, id) => {
        const a = LA.agent(id);
        LA.sheet({
          title: esc(a.name), body: `<div class="list"><button class="li" data-action="dup">${I('copy')}<div class="grow"><div class="t">Duplicate</div></div></button><button class="li" data-action="export">${I('upload')}<div class="grow"><div class="t">Export as JSON</div><div class="s">Planned for V1.x</div></div></button><button class="li" data-action="del" style="color:var(--bad)">${I('trash')}<div class="grow"><div class="t">Delete agent</div><div class="s">Also deletes its memories and agent-only knowledge</div></div></button></div>`,
          handlers: {
            dup: () => { const c = JSON.parse(JSON.stringify(a)); c.id = LA.uid('ag'); c.name = a.name + ' copy'; c.runs = 0; c.last = { status: 'never', at: null }; LA.S.agents.unshift(c); LA.save(); LA.closeSheet(); LA.go('agent-' + c.id); LA.toast('Duplicated'); },
            export: () => { LA.closeSheet(); LA.toast('Export is planned for a later release'); },
            del: () => LA.confirm({ title: 'Delete ' + esc(a.name) + '?', body: 'The agent, its ' + LA.S.memories.filter((x) => x.agent === a.id).length + ' memories and its run history are removed from this device.', ok: 'Delete agent', danger: true, onOk: () => { LA.S.agents = LA.S.agents.filter((x) => x.id !== a.id); LA.S.memories = LA.S.memories.filter((x) => x.agent !== a.id); LA.save(); LA.go('agents'); LA.toast('Agent deleted'); } })
          }
        });
      }
    }
  };

  /* ---------- memory ---------- */
  LA.screens.memory = {
    render(id) {
      const agents = LA.S.agents;
      const a = LA.agent(id) || agents.find((x) => x.memory.enabled) || agents[0];
      const mine = LA.S.memories.filter((x) => x.agent === a.id);
      const inbox = mine.filter((x) => x.status === 'PROPOSED'); const active = mine.filter((x) => x.status === 'ACTIVE').sort((x, y) => y.pinned - x.pinned || y.importance - x.importance);
      const allInbox = LA.S.memories.filter((x) => x.status === 'PROPOSED').length;
      return `${C.topbar({ title: 'Agent memory', sub: allInbox + ' waiting for review · stored on device', back: 'agents', actions: C.ib('info', 'explain', 'How memory works') })}
      <div class="scroll"><div class="body">
        <div class="chips scroller">${agents.map((x) => { const n = LA.S.memories.filter((m) => m.agent === x.id && m.status === 'PROPOSED').length; return `<button class="chip ${x.id === a.id ? 'on' : ''}" data-go="memory-${x.id}">${esc(x.name)}${n ? ` <span class="tag" style="height:18px;background:var(--accent);color:var(--accent-ink)">${n}</span>` : ''}</button>`; }).join('')}</div>
        <div class="card"><div class="row" style="gap:12px">${C.avatar(a, 'sm')}<div class="grow"><b>${esc(a.name)}</b><div class="xs faint">${active.length} active · ${inbox.length} proposed · ${a.memory.autoSave ? 'auto-save on' : 'approval required'}</div></div>${C.sw('mem-on', a.memory.enabled, 'mem-on', 'Memory for ' + a.name)}</div></div>
        ${!a.memory.enabled ? `<div class="banner warn">${I('info')}<span>Memory is off. ${esc(a.name)} will not remember anything between conversations. Existing items are kept until you delete them.</span></div>` : ''}
        ${inbox.length ? C.sectionH('Inbox · needs your approval') + `<div class="col" style="gap:10px">${inbox.map((x) => `<div class="card tight"><div class="mem-item"><span class="mem-kind ${x.kind}">${x.kind.slice(0, 4)}</span><div class="grow"><div class="small">${esc(x.content)}</div><div class="xs faint" style="margin-top:4px">From ${esc(x.from || 'a recent run')} · importance ${x.importance}/5</div></div></div><div class="row" style="margin-top:10px;gap:8px;justify-content:flex-end"><button class="btn btn-ghost btn-sm" data-action="reject" data-id="${x.id}">Discard</button><button class="btn btn-tonal btn-sm" data-action="approve" data-id="${x.id}">${I('check', 'icon-sm')}Remember</button></div></div>`).join('')}</div>` : ''}
        ${C.sectionH('Remembered')}
        <div class="list">${active.map((x) => `<div class="li mem-item" style="align-items:flex-start"><span class="mem-kind ${x.kind}">${x.kind.slice(0, 4)}</span><div class="grow"><div class="small" style="color:var(--text)">${esc(x.content)}</div><div class="xs faint" style="margin-top:3px">${x.pinned ? 'Pinned · ' : ''}used ${x.used}× · saved ${F.ago(x.created)}</div></div><div class="row" style="gap:0">${C.ib('pin', 'pin', x.pinned ? 'Unpin' : 'Pin', `data-id="${x.id}" style="${x.pinned ? 'color:var(--accent)' : ''}"`)}${C.ib('edit', 'edit-mem', 'Edit', `data-id="${x.id}"`)}${C.ib('trash', 'del-mem', 'Delete', `data-id="${x.id}"`)}</div></div>`).join('') || `<div class="li small muted">Nothing remembered yet.</div>`}</div>
        ${active.length ? `<button class="btn btn-danger" data-action="forget">${I('trash', 'icon-sm')}Forget everything ${esc(a.name)} remembers</button>` : ''}
        <p class="xs faint" style="margin:0">Memory is separate from knowledge bases: documents you import are never rewritten as memories.</p>
      </div></div>`;
    },
    changes: { 'mem-on': (el, ev, id) => { const a = LA.agent(id) || LA.S.agents.find((x) => x.memory.enabled) || LA.S.agents[0]; a.memory.enabled = el.checked; LA.save(); LA.refresh(); } },
    actions: {
      approve: (el) => { const m = LA.S.memories.find((x) => x.id === el.getAttribute('data-id')); m.status = 'ACTIVE'; LA.save(); LA.refresh(); LA.toast('Remembered'); },
      reject: (el) => { LA.S.memories = LA.S.memories.filter((x) => x.id !== el.getAttribute('data-id')); LA.save(); LA.refresh(); LA.toast('Discarded'); },
      pin: (el) => { const m = LA.S.memories.find((x) => x.id === el.getAttribute('data-id')); m.pinned = !m.pinned; LA.save(); LA.refresh(); },
      'del-mem': (el) => { const id = el.getAttribute('data-id'); LA.S.memories = LA.S.memories.filter((x) => x.id !== id); LA.save(); LA.refresh(); LA.toast('Deleted, including its search vector'); },
      'edit-mem': (el) => {
        const m = LA.S.memories.find((x) => x.id === el.getAttribute('data-id'));
        LA.sheet({ title: 'Edit memory', body: `<div class="field"><label for="mem-text">Memory</label><textarea class="textarea" id="mem-text" maxlength="280">${esc(m.content)}</textarea><span class="hint">Up to 280 characters. Re-embedded when saved.</span></div><div class="field"><span class="label">Type</span>${C.seg('Type', [['FACT', 'Fact'], ['PREFERENCE', 'Preference'], ['EPISODE', 'Episode']], m.kind, 'kind', true)}</div>`, actions: '<button class="btn btn-ghost" data-action="close-sheet">Cancel</button><button class="btn btn-primary" data-action="save-mem">Save</button>',
          handlers: { kind: (b) => { m._kind = b.getAttribute('data-v'); b.parentNode.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); }, 'save-mem': () => { const v = document.getElementById('mem-text').value.trim(); if (v) m.content = v; if (m._kind) { m.kind = m._kind; delete m._kind; } LA.save(); LA.closeSheet(); LA.refresh(); LA.toast('Memory updated'); } } });
      },
      forget: (el, ev, id) => { const a = LA.agent(id) || LA.S.agents.find((x) => x.memory.enabled); LA.confirm({ title: 'Forget everything?', body: `All memories of ${esc(a.name)} are permanently deleted from this device, including their search vectors and cached prompts.`, ok: 'Forget all', danger: true, onOk: () => { LA.S.memories = LA.S.memories.filter((x) => x.agent !== a.id); LA.save(); LA.refresh(); LA.toast('Memory cleared'); } }); },
      explain: () => LA.sheet({ title: 'How memory works', body: `<div class="list">${[['chat', 'Conversation memory', 'Messages of one chat plus a rolling summary once it outgrows the context window. Deleted with the chat.'], ['memory', 'Agent memory', 'Short facts, preferences and episode summaries per agent. Proposed by the agent after a run; you approve them here.'], ['knowledge', 'Shared knowledge', 'Documents in knowledge bases. Retrieved with citations, never changed by agents.']].map(([i, t, s]) => `<div class="li" style="align-items:flex-start">${I(i)}<div class="grow"><div class="t">${t}</div><div class="s">${s}</div></div></div>`).join('')}</div><p class="small muted" style="margin:0">Recall picks up to 5 memories by relevance, importance and recency (≤ 400 tokens). Pinned memories are always included.</p>` })
    }
  };
})();
