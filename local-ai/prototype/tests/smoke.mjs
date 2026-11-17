// Prototype smoke + interaction tests. Run: node local-ai/prototype/tests/smoke.mjs [--no-shots]
// Uses the Playwright + Chromium pre-installed in the environment (require('playwright')).
import { createRequire } from 'module';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const url = 'file://' + path.join(root, 'index.html');
const shotsDir = path.join(root, 'screenshots');
const SHOTS = !process.argv.includes('--no-shots');
fs.mkdirSync(shotsDir, { recursive: true });

const results = [];
const errors = [];
let page;
const step = async (name, fn) => {
  try { await fn(); results.push(['PASS', name]); }
  catch (e) { results.push(['FAIL', name + ' — ' + e.message.split('\n')[0]]); }
};
const assert = (c, m) => { if (!c) throw new Error(m); };
const go = async (h) => { await page.evaluate((x) => { location.hash = x; }, h); await page.waitForTimeout(250); };
const text = async (sel) => (await page.locator(sel).first().innerText()).trim();
const overflow = () => page.evaluate(() => {
  const bad = [];
  if (document.documentElement.scrollWidth > window.innerWidth + 1) bad.push('page ' + document.documentElement.scrollWidth);
  document.querySelectorAll('.scroll').forEach((s) => { if (s.scrollWidth > s.clientWidth + 1) bad.push('scroll ' + s.scrollWidth + '>' + s.clientWidth); });
  return bad;
});

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 412, height: 892 }, deviceScaleFactor: SHOTS ? 2 : 1 });
page = await ctx.newPage();
page.on('console', (m) => { if (m.type() === 'error' && !/fonts\.g/.test(m.text()) && !/ERR_/.test(m.text())) errors.push(m.text()); });
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
await page.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.abort());
await page.goto(url);
await page.evaluate(() => localStorage.clear());
await page.reload();
await page.waitForTimeout(300);
const shot = async (name) => { if (SHOTS) await page.screenshot({ path: path.join(shotsDir, name + '.png') }); };

await step('Onboarding: 4 steps to home with starter download', async () => {
  assert((await text('.onb h2')).includes('Your models'), 'welcome headline');
  await shot('01-welcome');
  await page.click('[data-action=onb-next]');
  await page.click('[data-action=onb-next]');
  await page.waitForSelector('[data-action=onb-next]:not([disabled])', { timeout: 4000 });
  await shot('01b-device-check');
  await page.click('[data-action=onb-next]');
  assert(await page.locator('input[name=starter]').count() >= 1, 'starter models listed');
  await page.click('[data-action=onb-finish]');
  await page.waitForTimeout(300);
  assert(page.url().endsWith('#home'), 'landed on home');
});

const SCREENS = await page.evaluate(() => LA.SCREENS);
for (const [h, title] of SCREENS) {
  await step(`Screen renders without overflow: ${title}`, async () => {
    await go(h);
    if (h === 'welcome') await page.evaluate(() => { LA.S.onboarded = false; });
    const view = await page.locator('.view').innerText();
    assert(view.length > 40, 'has content');
    const o = await overflow();
    assert(!o.length, 'horizontal overflow: ' + o.join(', '));
    const idx = String(SCREENS.findIndex((s) => s[0] === h) + 1).padStart(2, '0');
    if (h !== 'welcome') await shot(idx + '-' + title.toLowerCase().replace(/[^a-z]+/g, '-').replace(/-$/, ''));
  });
}
await page.evaluate(() => { LA.S.onboarded = true; LA.save(); });

await step('Model selection: switch active model and wait for load', async () => {
  await go('home');
  await page.click('[data-action=pick-model]');
  await page.click('.sheet [data-id=llama-3-2-3b]');
  await page.waitForFunction(() => LA.S.modelLoaded && LA.S.activeModel === 'llama-3-2-3b', null, { timeout: 5000 });
  assert((await text('.card.hero')).includes('Llama 3.2 3B'), 'hero shows new model');
});

await step('Model details: context slider updates memory estimate', async () => {
  await go('model-qwen3-4b');
  const before = await page.locator('.verdict').locator('xpath=..').innerText();
  await page.locator('#r-ctx').fill('15');
  await page.locator('#r-ctx').dispatchEvent('change');
  await page.waitForTimeout(200);
  const after = await page.locator('.verdict').locator('xpath=..').innerText();
  assert(before !== after && after.includes('32K'), 'estimate changed for 32K context');
});

await step('Device profile: 8 GB marks Llama 3.1 8B as not recommended', async () => {
  await go('prefs');
  await page.click('[data-action=dev][data-v="8"]');
  await go('models');
  const card = await page.locator('[data-model=llama-3-1-8b]').innerText();
  assert(card.includes('Not recommended'), 'verdict on 8 GB: ' + card.replace(/\n/g, ' '));
  await go('prefs');
  await page.click('[data-action=dev][data-v="12"]');
});

await step('Downloads: start, pause and resume', async () => {
  await go('models');
  await page.click('[data-action=download][data-id=mistral-7b]');
  await go('downloads');
  await page.click('[data-action=pause]');
  assert(await page.locator('[data-action=resume]').count() >= 1, 'resume visible');
  await page.locator('[data-action=resume]').first().click();
  assert(await page.locator('[data-action=pause]').count() >= 1, 'pause visible again');
});

await step('Chat: streaming answer with citations, stop and regenerate', async () => {
  await go('chat-c-lease');
  await page.fill('#composer-input', 'What is the notice period in my lease?');
  await page.click('[data-action=send]');
  await page.waitForSelector('[data-action=stop]', { timeout: 2000 });
  await page.waitForSelector('[data-action=stop]', { state: 'detached', timeout: 15000 });
  const last = await page.locator('.msg.ai').last().innerText();
  assert(/60 days/.test(last), 'answer mentions notice');
  assert(await page.locator('.msg.ai').last().locator('.source').count() >= 2, 'sources rendered');
  await page.locator('.msg.ai').last().locator('.source').first().click();
  assert(await page.locator('.sheet').count() === 1, 'source sheet opened');
  await page.click('.sheet [data-action=close-sheet]');
  await page.click('[data-action=regen]');
  await page.waitForSelector('[data-action=stop]', { timeout: 2000 });
  await page.click('[data-action=stop]');
  assert((await page.locator('.msg.ai').last().innerText()).includes('Generation stopped'), 'stopped note');
  await shot('18-chat-streaming-stopped');
});

await step('Chat: knowledge base picker and agent mode toggle', async () => {
  await page.click('[data-action=new-chat]').catch(() => {});
  await go('chat');
  await page.click('[data-action=new]');
  await page.click('[data-action=attach]');
  await page.click('.sheet [data-action=pick-kb]');
  await page.locator('.sheet input[value=kb-research]').check();
  await page.click('.sheet [data-action=close-sheet].btn');
  assert((await text('[data-action=pick-kb]')).includes('On-device ML papers'), 'kb chip shows selection');
  await page.click('[data-action=attach]');
  await page.click('.sheet [data-action=pick-agent]');
  await page.click('.sheet [data-v=supervisor]');
  assert((await text('[data-action=pick-agent]')).includes('Supervisor'), 'agent mode on');
});

await step('Chat → workflow: agent-mode message starts the supervisor run', async () => {
  await page.evaluate(() => { LA.wf.speed = 4; });
  await page.fill('#composer-input', 'Compare runtimes and draft the interface');
  await page.click('[data-action=send]');
  await page.waitForSelector('.wf-card', { timeout: 2000 });
  await page.waitForFunction(() => LA.wf.status === 'done', null, { timeout: 30000 });
  await page.waitForTimeout(700);
  assert((await page.locator('.msg.ai').last().innerText()).includes('llama.cpp'), 'final answer delivered to chat');
});

await step('Agent creation: validation, then create from Coding template', async () => {
  await go('agent-new');
  await page.click('[data-action=tpl][data-id=coding]');
  await page.click('[data-action=next]');
  await page.click('[data-action=save]');
  assert((await page.locator('.err').count()) >= 1, 'name required error');
  await page.fill('#a-name', 'Nova');
  await page.locator('.check input[value=kb-android]').check();
  await shot('19-create-agent-form');
  await page.click('[data-action=save]');
  await page.waitForTimeout(300);
  assert(/#agent-ag-/.test(page.url()), 'navigated to agent details');
  assert((await page.locator('.view').innerText()).includes('Nova'), 'details show name');
  await go('agents');
  assert((await page.locator('.agent-card').first().innerText()).includes('Nova'), 'card in studio');
});

await step('Knowledge base creation: validation, files, indexing progress', async () => {
  await go('kb-new');
  await page.click('[data-action=create]');
  assert((await page.locator('.err').count()) === 2, 'two validation errors');
  await page.fill('#k-name', 'Q3 planning');
  await page.click('[data-action=add-files]');
  await page.locator('.sheet input[type=checkbox]').nth(0).check();
  await page.locator('.sheet input[type=checkbox]').nth(5).check();
  await page.click('.sheet [data-action=pick-ok]');
  await page.click('[data-action=create]');
  await page.waitForTimeout(300);
  assert(/#kb-kb-/.test(page.url()), 'on kb detail');
  const v = await page.locator('.view').innerText();
  assert(/Extracting|Queued|Chunking|Embedding|Indexing/.test(v), 'indexing visible');
  assert(v.includes('Failed'), 'scanned PDF fails with OCR message');
  await page.waitForTimeout(1500);
  await shot('20-kb-indexing');
});

await step('Semantic search returns ranked passages', async () => {
  await go('knowledge');
  await page.fill('#kb-q', 'KV cache memory');
  await page.waitForTimeout(400);
  assert(await page.locator('#kb-results .card').count() >= 3, 'results');
});

await step('Workflow: transient failure retries and completes', async () => {
  await go('workflow');
  await page.evaluate(() => { LA.wf.speed = 4; LA.wf.fail = 'transient'; });
  await page.click('[data-action=run]');
  await page.waitForTimeout(2500);
  await shot('21-workflow-running');
  await page.waitForFunction(() => LA.wf.status === 'done', null, { timeout: 30000 });
  assert(await page.evaluate(() => LA.wf.nodes.code.attempt === 2), 'second attempt used');
  assert((await page.locator('#wf-log').innerText()).includes('retrying'), 'retry logged');
});

await step('Workflow: permanent failure skips review, partial answer, retry step', async () => {
  await page.evaluate(() => { LA.wf.fail = 'permanent'; });
  await page.click('[data-action=run]');
  await page.waitForFunction(() => LA.wf.status === 'partial', null, { timeout: 30000 });
  await page.waitForTimeout(700);
  assert(await page.evaluate(() => LA.wf.nodes.rev.state === 'skipped'), 'review skipped');
  await shot('22-workflow-partial');
  await page.click('[data-action=retry]');
  await page.waitForFunction(() => LA.wf.status === 'done', null, { timeout: 30000 });
});

await step('Workflow: cancel mid-run', async () => {
  await page.evaluate(() => { LA.wf.fail = 'none'; LA.wf.speed = 1; });
  await page.click('[data-action=run]');
  await page.waitForTimeout(1200);
  await page.click('[data-action=cancel]');
  assert(await page.evaluate(() => LA.wf.status === 'cancelled'), 'cancelled');
});

await step('Tools: safe calculator and permission change', async () => {
  await go('tools');
  await page.fill('#calc-in', '(1250 * 12) * 0.18');
  await page.click('[data-action=calc]');
  assert((await text('#calc-out')).includes('2,700'), 'calc result');
  await page.fill('#calc-in', 'alert(1)');
  await page.click('[data-action=calc]');
  assert((await text('#calc-out')).startsWith('ToolError'), 'rejects non-arithmetic');
  await page.click('[data-action=perm][data-id=file_search][data-v=OFF]');
  assert(await page.evaluate(() => LA.tool('file_search').permission === 'OFF'), 'permission saved');
});

await step('Memory: approve proposal, pin, delete', async () => {
  await go('memory-ag-juno');
  await page.click('[data-action=approve]');
  assert(await page.evaluate(() => LA.S.memories.find((m) => m.id === 'm5').status === 'ACTIVE'), 'approved');
  const n = await page.evaluate(() => LA.S.memories.length);
  await page.locator('[data-action=del-mem]').first().click();
  assert(await page.evaluate((x) => LA.S.memories.length === x - 1, n), 'deleted');
});

await step('Light theme renders', async () => {
  await go('prefs');
  await page.click('[data-action=look][data-v=light]');
  await go('home');
  await shot('23-home-light');
  const bg = await page.evaluate(() => getComputedStyle(document.querySelector('.device')).backgroundColor);
  assert(bg === 'rgb(243, 244, 248)', 'light bg ' + bg);
  await go('prefs');
  await page.click('[data-action=look][data-v=dark]');
});

await step('Photo: text-only model refuses, switch to SmolVLM, photo is explained', async () => {
  await go('chat'); await page.click('[data-action=new]'); await page.waitForTimeout(250);
  await page.evaluate(() => { const c = LA.conv(LA.cur().param); c.model = 'qwen3-4b'; LA.save(); LA.refresh(); });
  await page.click('[data-action=attach]');
  assert(await page.locator('.sheet .photo-grid button').count() === 3, 'sample photos offered');
  await page.click('.sheet [data-action=photo-sample][data-k=bill]');
  assert(await page.locator('.pending-photo').count() === 1, 'pending photo shown');
  await page.fill('#composer-input', 'When is this due?');
  await page.click('[data-action=send]');
  assert((await page.locator('.msg.ai').last().innerText()).includes("can't see images"), 'no-vision message');
  await page.click('[data-action=use-vision]');
  await page.waitForSelector('[data-action=stop]', { state: 'detached', timeout: 15000 });
  await page.waitForFunction(() => /28 Nov 2026/.test(document.querySelectorAll('.msg.ai')[document.querySelectorAll('.msg.ai').length - 1].innerText), null, { timeout: 15000 });
  assert(await page.locator('.msg.user img.photo').count() === 1, 'photo in user bubble');
  await page.waitForFunction(() => LA.S.modelLoaded, null, { timeout: 5000 });
  await shot('24-photo-explained');
});

await step('Photo: Home "Ask a photo" opens a vision chat with the photo attached', async () => {
  await go('home');
  await page.click('[data-action=ask-photo]');
  await page.click('.sheet [data-action=photo-sample][data-k=plant]');
  await page.waitForTimeout(250);
  assert(/#chat-/.test(page.url()), 'opened chat');
  assert(await page.locator('.pending-photo').count() === 1, 'photo waiting to send');
  assert(await page.evaluate(() => LA.isVision(LA.conv(LA.cur().param).model)), 'vision model chosen');
  await page.click('[data-action=send]');
  await page.waitForFunction(() => /fungal/.test(document.querySelector('.msg.ai:last-child') ? document.querySelector('.msg.ai:last-child').innerText : ''), null, { timeout: 15000 });
});

await step('Voice: dictation types into the composer; read aloud and save as Word', async () => {
  await go('chat-c-flow');
  await page.click('[data-action=dictate]');
  assert(await page.locator('.ibtn.mic.on').count() === 1, 'mic active');
  await page.waitForFunction(() => document.getElementById('composer-input').value.includes('photo show'), null, { timeout: 5000 });
  await page.fill('#composer-input', '');
  await page.locator('[data-action=speak-msg]').last().click();
  assert(await page.locator('[aria-label="Stop reading"]').count() === 1, 'reading state');
  await page.locator('[data-action=speak-msg]').last().click();
  const before = await page.evaluate(() => LA.S.exports.length);
  await page.locator('[data-action=save-msg]').last().click();
  await page.click('.sheet [data-action=sv][data-f=docx]');
  assert(await page.evaluate((n) => LA.S.exports.length === n + 1 && LA.S.exports[0].name.endsWith('.docx'), before), 'docx export added');
  await page.click('.sheet [data-action=sv-open]');
  assert(await page.locator('.sheet .paper.docx .page').count() === 1, 'document preview');
  await page.click('.sheet [data-action=close-sheet]');
});

await step('Voice conversation: listen → think → speak → transcript saved; tap interrupts', async () => {
  await go('home'); await page.click('[data-action=talk]');
  await page.waitForTimeout(200);
  assert((await text('.v-phase')) === 'Tap to talk', 'idle');
  await page.click('[data-action=orb]');
  await page.waitForFunction(() => document.querySelector('.voice').dataset.phase === 'listening');
  await page.waitForTimeout(700); await shot('25-voice-listening');
  await page.waitForFunction(() => document.querySelector('.voice').dataset.phase === 'speaking', null, { timeout: 8000 });
  await page.waitForTimeout(600); await shot('26-voice-speaking');
  assert((await text('.v-reply')).length > 10, 'reply text streams');
  // Tap while speaking interrupts and listens again (hands-free may already have started listening).
  // Tap while speaking (atomic: the animated orb never settles, so a real click can land after the answer ends).
  await page.evaluate(() => { if (document.querySelector('.voice').dataset.phase === 'speaking') document.querySelector('[data-action=orb]').click(); });
  await page.waitForFunction(() => document.querySelector('.voice').dataset.phase === 'listening', null, { timeout: 8000 });
  await page.click('[data-action=orb]');   // tap while listening stops
  assert((await text('.v-phase')) === 'Tap to talk', 'back to idle');
  assert(await page.evaluate(() => LA.S.conversations.some((c) => c.messages.some((m) => m.voice))), 'voice turn saved in a conversation');
});

await step('Document Studio: write a letter → save PDF; convert Word → PDF with translation', async () => {
  await go('studio');
  await page.click('[data-action=st-write]');
  assert((await page.locator('.toast').innerText()).includes('Describe'), 'validation');
  await page.fill('#st-req', 'leave from 20 to 22 November for a family function');
  await page.click('[data-action=st-write]');
  await page.waitForSelector('[data-action=st-save][data-f=pdf]', { timeout: 15000 });
  assert((await text('.doc-preview')).includes('family function'), 'draft uses the request');
  await shot('27-studio-write');
  await page.click('[data-action=st-save][data-f=pdf]');
  await page.click('[data-action=st-tab][data-v=convert]');
  await page.click('[data-action=st-src][data-v=s-offer]');
  assert(await page.locator('[data-action=st-target][data-v=pdf].on').count() === 1, 'Word defaults to PDF');
  await page.click('[data-action=st-action][data-v=translate]');
  await page.fill('#st-tlang', 'Tamil');
  await page.click('[data-action=st-convert]');
  await page.waitForSelector('.sheet .paper.pdf', { timeout: 15000 });
  await shot('28-studio-converted');
  await page.click('.sheet [data-action=close-sheet]');
  await page.click('[data-action=st-tab][data-v=files]');
  const files = await text('.list');
  assert(files.includes('Offer letter – Tamil.pdf') && files.includes('Leave request.pdf'), 'both exports listed: ' + files.replace(/\n/g, ' | '));
  await page.locator('[data-action=ex-del]').first().click();
  await page.click('.sheet [data-action=sheet-ok]');
  assert(!(await text('.list')).includes('Offer letter – Tamil.pdf'), 'deleted');
});

await step('Settings: voice language and read-aloud persist', async () => {
  await go('prefs');
  await page.selectOption('#s-vlang', 'ta-IN');
  await page.click('[data-action=vrate][data-v="1.25"]');
  assert(await page.evaluate(() => LA.S.settings.voiceLang === 'ta-IN' && LA.S.settings.speechRate === 1.25), 'saved');
  assert((await page.locator('.view').innerText()).includes('No SMS, contacts or call-log access'), 'phone data note');
});

await step('Scam check: same verdicts as the app’s rules; explanation; Tamil', async () => {
  const levels = await page.evaluate(() => [
    'VM-SBIUPD Dear Customer, your SBI account will be BLOCKED today. Update your KYC immediately at http://sbi-kyc-update.xyz/login or call 9876543210. Do not ignore.',
    'You have received Rs 5,000 cashback! Enter your UPI PIN to receive the amount.',
    'This is CBI. A parcel in your name with drugs was seized at customs. You are under digital arrest. Stay on video call.',
    'Part time job! Like YouTube videos and earn Rs 3000 per day. Join telegram for prepaid task.',
    'Share the OTP you just received to confirm your delivery.',
    'உங்கள் மின்சார கட்டணம் செலுத்தப்படவில்லை. இன்று இரவு 9 மணிக்கு மின் இணைப்பு துண்டிக்கப்படும். அழைக்கவும் 9876543210',
    'Your OTP for login is 482913. Do not share it with anyone. Valid for 5 minutes. - HDFC Bank',
    'Hi Karthik, dinner at 8 tonight? Mom made biryani.',
    'Your Amazon order #402-1 has shipped. Track at https://www.amazon.in/orders'
  ].map((t) => LA.scamCheck(t).level));
  assert(levels.join(',') === 'HIGH,HIGH,HIGH,HIGH,HIGH,HIGH,LOW,LOW,LOW', 'levels ' + levels.join(','));
  await go('home'); await page.click('[data-action=go-scam]');
  await page.click('[data-action=sc-sample][data-i="0"]');
  assert((await text('.verdict-card')).includes('Likely a scam'), 'verdict');
  assert(await page.locator('.flag').count() >= 3, 'red flags listed');
  await page.waitForFunction(() => /OTP/.test(document.getElementById('sc-explain').textContent), null, { timeout: 8000 });
  await page.click('[data-action=sc-lang][data-v="ta-IN"]');
  await page.waitForFunction(() => /மோசடி/.test(document.getElementById('sc-explain').textContent), null, { timeout: 8000 });
  await page.waitForTimeout(400); await shot('30-scam-check');
  assert((await page.locator('.view').innerText()).includes('1930'), 'helpline advice');
  await page.click('[data-action=sc-sample][data-i="3"]');
  assert((await text('.verdict-card')).includes('No common scam signs'), 'genuine OTP SMS is not flagged');
});

await step('Read it to me: photo → text → simple explanation in Hindi', async () => {
  await go('home'); await page.click('[data-action=go-read]');
  await page.click('[data-action=rd-photo]');
  await page.click('.sheet [data-action=photo-sample][data-k=bill]');
  await page.waitForFunction(() => document.getElementById('rd-explain') && /2,184/.test(document.getElementById('rd-explain').textContent), null, { timeout: 8000 });
  assert((await page.locator('.view').innerText()).includes('Consumer no. 04-118-2291'), 'text found shown');
  await page.click('[data-action=rd-lang][data-v="hi-IN"]');
  await page.waitForFunction(() => /बिजली/.test(document.getElementById('rd-explain').textContent), null, { timeout: 8000 });
  await page.waitForTimeout(600); await shot('31-read-it-to-me');
  await page.click('[data-action=rd-photo]');
  await page.click('.sheet [data-action=photo-sample][data-k=plant]');
  assert((await page.locator('.view').innerText()).includes('No readable text'), 'photo without text handled');
});

await step('Chat: drawer with sections, pinned chats, search; pill composer switches voice ↔ send', async () => {
  await go('chat-c-lease');
  assert(await page.locator('.pill .voicebtn').isVisible(), 'voice button when empty');
  await page.fill('#composer-input', 'hello');
  assert(await page.locator('.pill .send').isVisible() && !(await page.locator('.pill .voicebtn').isVisible()), 'send replaces voice when typing');
  await page.fill('#composer-input', '');
  await page.click('[data-action=drawer]');
  assert(await page.locator('.drawer').count() === 1, 'drawer open');
  assert((await page.locator('.drawer').innerText()).includes('Scam check'), 'sections listed');
  await page.locator('.d-chat[data-id=c-flow] i').click();
  await page.click('.sheet [data-action=d-pin]');
  assert((await page.locator('.drawer').innerText()).includes('Pinned'), 'pinned section');
  await page.waitForTimeout(400); await shot('32-chat-drawer');
  await page.click('[data-d=search]'); await page.fill('#d-q', 'coorg');
  assert(await page.locator('.d-chat').count() === 1, 'search filters chats');
  await page.click('[data-d=close]', { position: { x: 395, y: 400 } });   // the visible strip of the scrim beside the drawer
  await page.click('[data-action=new-here]'); await page.waitForTimeout(250);
  assert((await page.locator('.chat-empty h2').innerText()).includes('What can I help with?'), 'empty state');
  await shot('33-chat-empty');
});

await step('Voice: talk over the assistant to interrupt; natural voice picker', async () => {
  await go('home'); await page.click('[data-action=talk]'); await page.waitForTimeout(200);
  await page.click('[data-action=orb]');
  await page.waitForFunction(() => document.querySelector('.voice').dataset.phase === 'speaking', null, { timeout: 8000 });
  assert((await text('.v-hint')).includes('Just start talking'), 'barge-in hint');
  await page.click('[data-action=talk-over]');
  await page.waitForFunction(() => document.querySelector('.voice').dataset.phase === 'listening', null, { timeout: 3000 });
  await page.click('[data-action=orb]');
  await go('prefs');
  await page.click('[data-action=v-dl][data-v=amy]');
  assert(await page.evaluate(() => LA.S.settings.ttsVoice === 'amy'), 'natural voice selected');
  await page.click('[data-action=barge][data-v=SENSITIVE]');
  assert(await page.evaluate(() => LA.S.settings.bargeIn === 'SENSITIVE'), 'barge-in sensitivity saved');
});

await step('Automatic tools: date, scam check and a PDF are picked by the AI itself', async () => {
  await go('chat'); await page.click('[data-action=new]'); await page.waitForTimeout(250);
  const ask = async (t) => {
    const n = await page.locator('.msg.ai').count();
    await page.fill('#composer-input', t); await page.click('[data-action=send]');
    await page.waitForFunction((k) => document.querySelectorAll('.msg.ai').length > k && !document.querySelector('[data-action=stop]'), n, { timeout: 15000 });
    return page.locator('.msg.ai').last().innerText();
  };
  assert((await ask("What's the date today?")).includes('Date & time · used automatically'), 'date tool');
  assert((await ask('Dear Customer, your SBI account will be BLOCKED today. Update KYC at http://sbi-kyc-update.xyz')).includes('Scam check · used automatically'), 'scam tool');
  const last = await ask('Write a leave letter for 20-22 Nov and save it as a PDF');
  assert(last.includes('Create document') && /Leave request( \(\d+\))?\.pdf/.test(last), 'document created: ' + last.replace(/\n/g, ' | ').slice(0, 400));
  await page.locator('[data-action=open-file]').last().click();
  assert(await page.locator('.sheet .paper.pdf').count() === 1, 'opens the PDF preview');
  await page.click('.sheet [data-action=close-sheet]');
  await shot('34-auto-tools');
});

await step('Launch animation plays and gets out of the way', async () => {
  await go('home');
  await page.evaluate(() => LA.splash(1900));
  assert(await page.locator('.splash').count() === 1, 'splash shown');
  await page.waitForTimeout(1700); await shot('29-launch-animation');
  await page.waitForFunction(() => !document.querySelector('.splash'), null, { timeout: 3000 });
});

await step('No console errors during the session', async () => { assert(!errors.length, errors.slice(0, 3).join(' | ')); });

if (SHOTS) {
  const wide = await browser.newContext({ viewport: { width: 1440, height: 960 }, deviceScaleFactor: 2 });
  const p2 = await wide.newPage();
  await p2.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.abort());
  await p2.goto(url + '#workflow');
  await p2.evaluate(() => { localStorage.clear(); LA.S = LA.seed(); LA.S.onboarded = true; LA.wf.speed = 2; LA.render(); });
  await p2.click('[data-action=run]');
  await p2.waitForTimeout(5000);
  await p2.screenshot({ path: path.join(shotsDir, '00-desktop-overview.png') });
  await wide.close();
}
await browser.close();

const fails = results.filter((r) => r[0] === 'FAIL');
console.log(results.map((r) => r.join('  ')).join('\n'));
console.log(`\n${results.length - fails.length}/${results.length} passed`);
process.exit(fails.length ? 1 : 0);
