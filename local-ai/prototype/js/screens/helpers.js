/* Scam check (real rules, ported from the app's ScamCheck.kt) and "Read it to me" (simulated OCR + explanation). */
window.LA = window.LA || {};
(function () {
  const I = (n, c) => LA.icon(n, c); const C = LA.C; const esc = LA.esc;
  LA.screens = LA.screens || {};

  /* ---------- scam rules: same patterns and weights as the Android app ---------- */
  const R = (id, title, why, weight, patterns, unless) => ({ id, title, why, weight, patterns: patterns.map((p) => new RegExp(p, 'i')), unless: unless ? new RegExp(unless, 'i') : null });
  const RULES = [
    R('otp', 'Asks for an OTP or PIN', 'Banks, UPI apps and delivery companies never ask you to share an OTP, PIN or CVV.', 7,
      ['(share|send|tell|give|forward|provide)\\b.{0,25}\\b(otp|one[- ]time password|pin|cvv|mpin)', '(otp|pin|cvv)\\b.{0,25}\\b(share|send|tell|forward|provide)\\b', 'ओटीपी.{0,20}(बताएं|भेजें|शेयर)', 'ஓடிபி.{0,20}(பகிர|சொல்ல|அனுப்ப)'],
      "(do not|don't|never|dont)\\s+(share|disclose|tell)|किसी के साथ साझा न|யாருடனும் பகிர வேண்டாம்"),
    R('pin_receive', 'PIN to “receive” money', 'You never need to enter your UPI PIN to receive money. Entering it sends money out.', 7,
      ['(enter|use|type)\\b.{0,20}\\b(upi )?pin\\b.{0,40}\\b(receive|get|credit|refund|cashback)', '(receive|get|claim)\\b.{0,30}\\b(enter|scan)\\b.{0,20}\\b(pin|qr)', 'collect request']),
    R('remote', 'Asks you to install a screen-sharing app', 'Apps like AnyDesk or TeamViewer let a stranger see and control your phone, including your bank apps.', 7,
      ['\\b(anydesk|teamviewer|quick ?support|rustdesk|airdroid|screen ?shar(e|ing))\\b', '\\.apk\\b', 'download (this|the) app']),
    R('arrest', 'Threat from “police”, CBI, customs or TRAI', 'Officials never arrest anyone over a video call or ask for money to “settle” a case. “Digital arrest” is always a scam.', 7,
      ['digital arrest', '\\b(cbi|narcotics|ncb|ed officer|enforcement directorate|customs|cyber ?cell|trai|police)\\b.{0,80}\\b(case|arrest|warrant|seized|illegal|drugs|suspend)', '(parcel|courier|fedex|dhl).{0,80}(drugs|illegal|seized|passports?)', 'डिजिटल अरेस्ट']),
    R('kyc', 'Account blocked / KYC update', 'Banks don’t block accounts by SMS or ask you to update KYC through a link. Use only the bank’s own app or branch.', 4,
      ['\\b(kyc|pan( card)?|aadhaar)\\b.{0,40}\\b(update|expire|expired|pending|link|verify|suspend|block)', 'account.{0,30}\\b(blocked|block|suspended|deactivated|frozen|closed)\\b', '(sim|number).{0,30}(blocked|deactivated|suspended)', 'केवाईसी', 'கேஒய்சி', 'கணக்கு.{0,20}முடக்க']),
    R('power', 'Electricity will be cut tonight', 'Electricity boards do not message you from personal numbers asking you to call an “officer” or pay through a link.', 5,
      ['(electricity|power|light|bijli|eb) (bill|connection|supply).{0,80}(disconnect|cut|discontinue|tonight|today)', '(disconnect|cut).{0,40}(electricity|power) (connection|supply)', 'बिजली.{0,40}(कट|काट)', 'மின்.{0,40}(துண்டிக்க|நிறுத்த)']),
    R('prize', 'Prize, lottery or free gift', 'You can’t win a lottery or prize you never entered. They ask for a “fee” or your bank details next.', 4,
      ['\\b(congratulations|you have won|you won|winner|lottery|lucky draw|kbc|jackpot|prize money|free gift)\\b', 'लॉटरी', 'பரிசு']),
    R('job', 'Easy money / task job', 'Pay-per-like, review or “task” jobs pay small amounts first, then ask you to deposit money you never get back.', 6,
      ['(work from home|part[- ]time job|daily (income|earn)|earn (rs\\.?|₹|inr)?\\s?\\d[\\d,]*\\s?(per|a|/) ?(day|hour|task))', '\\b(like|rate|review)\\b.{0,30}\\b(videos?|hotels?|products?)\\b.{0,40}\\b(earn|paid|salary|commission)', '(prepaid|vip) task', 'telegram.{0,40}(task|job|earn)', 'registration fee']),
    R('invest', 'Guaranteed or doubled returns', 'No real investment guarantees high or doubled returns. This is how fake trading and crypto apps start.', 5,
      ['(double|triple) (your )?money', 'guaranteed (returns?|profit)', '\\b\\d{2,3}\\s?% (daily|weekly|monthly) (return|profit)', '(crypto|trading|ipo|stock).{0,40}(tips|group|guaranteed|sure shot)']),
    R('pay_to_get', 'Pay a fee to receive money', 'Paying a “processing”, “release” or “tax” fee to get a refund, loan or prize is a classic scam.', 5,
      ['(processing|release|clearance|registration|verification|gst|tax|insurance) (fee|charge|charges|amount)', 'pay.{0,30}(to (receive|release|unlock|claim|activate))']),
    R('urgent', 'Pressure to act now', 'Scammers rush you so you don’t stop to check. Real organisations give you time.', 2,
      ['\\b(immediately|urgent(ly)?|within \\d+ (hours?|hrs|minutes?|mins)|today itself|last (warning|chance|reminder)|act now|tonight)\\b', 'तुरंत', 'உடனடியாக|இன்று இரவு']),
    R('call_number', 'Asks you to call or WhatsApp a mobile number', 'Organisations use official helplines, not personal mobile numbers.', 2, ['(call|contact|whatsapp|message)\\b.{0,25}(\\+?91[- ]?)?[6-9]\\d{9}\\b']),
    R('refund', 'Unexpected refund or wrong transfer', '“I sent money by mistake, please return it” and surprise refunds are used to trick you into paying.', 3,
      ['(sent|transferred|credited).{0,30}(by mistake|wrongly)', 'refund.{0,40}(click|link|process|claim)', 'income tax refund'])
  ];
  const SHORT = ['bit.ly', 'tinyurl.com', 'cutt.ly', 'rb.gy', 't.ly', 'is.gd', 'shorturl.at', 'goo.gl', 'ow.ly', 'tiny.cc', 's.id', 'rebrand.ly', 't.co', 'wa.me'];
  const RISKY = ['xyz', 'top', 'click', 'icu', 'live', 'shop', 'online', 'site', 'link', 'vip', 'buzz', 'cc', 'info'];
  const BRANDS = ['sbi', 'hdfc', 'icici', 'axis', 'kotak', 'paytm', 'phonepe', 'gpay', 'amazon', 'flipkart', 'npci', 'uidai', 'incometax', 'irctc', 'tneb', 'tangedco', 'indiapost', 'airtel', 'jio', 'lic', 'epfo'];
  const OFFICIAL = ['sbi.co.in', 'onlinesbi.sbi', 'hdfcbank.com', 'icicibank.com', 'axisbank.com', 'kotak.com', 'paytm.com', 'phonepe.com', 'pay.google.com', 'amazon.in', 'amazon.com', 'flipkart.com', 'npci.org.in', 'uidai.gov.in', 'incometax.gov.in', 'irctc.co.in', 'tangedco.org', 'tnebltd.gov.in', 'indiapost.gov.in', 'airtel.in', 'jio.com', 'licindia.in', 'epfindia.gov.in'];
  const URL_RE = /\b((?:https?:\/\/|www\.)[^\s<>"']+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|in|net|org|xyz|top|click|info|icu|live|shop|online|site|link|ly|me|co|cc|io|vip|buzz|app)(?:\/[^\s<>"']*)?)/gi;
  const host = (l) => l.toLowerCase().replace(/^https?:\/\//, '').replace(/^www\./, '').split(/[/?:]/)[0];
  LA.scamCheck = function (text) {
    const t = text.replace(/\n/g, ' '); const flags = [];
    RULES.forEach((r) => {
      let m = null; for (const p of r.patterns) { m = t.match(p); if (m) break; }
      if (!m || (r.unless && r.unless.test(t))) return;
      flags.push({ id: r.id, title: r.title, why: r.why, weight: r.weight, evidence: m[0].trim().slice(0, 80) });
    });
    const links = [...new Set((t.match(URL_RE) || []).map((l) => l.replace(/[.,);]+$/, '')))];
    links.forEach((l) => {
      const h = host(l); const tld = h.split('.').pop();
      if (SHORT.includes(h)) flags.push({ id: 'short', title: 'Shortened link', why: 'Short links hide where they really go. Don’t open them from unknown senders.', weight: 3, evidence: l });
      else if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) flags.push({ id: 'ip', title: 'Link to a bare IP address', why: 'Real companies never send links to raw number addresses.', weight: 5, evidence: l });
      else if (BRANDS.some((b) => h.includes(b)) && !OFFICIAL.some((o) => h === o || h.endsWith('.' + o))) flags.push({ id: 'lookalike', title: 'Fake-looking company link', why: 'The link uses a well-known name but is not that company’s real website.', weight: 6, evidence: l });
      else if (RISKY.includes(tld)) flags.push({ id: 'tld', title: 'Unusual website address', why: 'Addresses ending in .' + tld + ' are cheap and often used for scams.', weight: 3, evidence: l });
      if (/^http:\/\//i.test(l)) flags.push({ id: 'http', title: 'Unsecured link (http)', why: 'The link is not encrypted; never enter details on such a page.', weight: 1, evidence: l });
    });
    const uniq = []; flags.forEach((f) => { if (!uniq.some((u) => u.id === f.id)) uniq.push(f); });
    uniq.sort((a, b) => b.weight - a.weight);
    const score = uniq.reduce((s, f) => s + f.weight, 0); const critical = uniq.filter((f) => f.weight >= 5).length;
    const level = uniq.some((f) => f.weight >= 6) || (critical && score >= 7) || score >= 8 ? 'HIGH' : score >= 3 || critical ? 'MEDIUM' : 'LOW';
    return { level, score, flags: uniq };
  };
  const ADVICE = ['Don’t open links, call numbers or install apps from this message.', 'Never share an OTP, UPI PIN, CVV or password with anyone.',
    'Check by calling the official number on the bank’s website, card or app — not the one in the message.', 'Lost money? Call <b>1930</b> (National Cyber Crime Helpline) immediately, or report at cybercrime.gov.in.',
    'Report fraud calls and messages on Sanchar Saathi (Chakshu) at sancharsaathi.gov.in.'];
  const SAMPLES = [
    ['KYC SMS', 'VM-SBIUPD Dear Customer, your SBI account will be BLOCKED today. Update your KYC immediately at http://sbi-kyc-update.xyz/login or call 9876543210.'],
    ['Electricity (Tamil)', 'உங்கள் மின்சார கட்டணம் செலுத்தப்படவில்லை. இன்று இரவு 9 மணிக்கு மின் இணைப்பு துண்டிக்கப்படும். அழைக்கவும் 9876543210'],
    ['Task job', 'Part time job! Like YouTube videos and earn Rs 3000 per day. Join telegram for prepaid task.'],
    ['Real bank SMS', 'Your OTP for login is 482913. Do not share it with anyone. Valid for 5 minutes. - HDFC Bank']
  ];
  const EXPLAIN = {
    HIGH: { '': 'This message is almost certainly a scam. ', 'ta-IN': 'இது பெரும்பாலும் மோசடி செய்தி. ', 'hi-IN': 'यह लगभग निश्चित रूप से धोखाधड़ी वाला संदेश है। ' },
    MEDIUM: { '': 'This message looks suspicious. ', 'ta-IN': 'இந்த செய்தி சந்தேகத்திற்குரியது. ', 'hi-IN': 'यह संदेश संदिग्ध लगता है। ' },
    LOW: { '': 'I found no common scam signs, but that does not prove it is safe. ', 'ta-IN': 'பொதுவான மோசடி அறிகுறிகள் இல்லை, ஆனாலும் கவனமாக இருங்கள். ', 'hi-IN': 'आम धोखाधड़ी के संकेत नहीं मिले, फिर भी सावधान रहें। ' }
  };
  const S = { text: '', report: null, lang: '', explain: '', timer: null };

  function explainFor(r, lang) {
    const head = (EXPLAIN[r.level][lang] || EXPLAIN[r.level]['']);
    if (lang === 'ta-IN') return head + (r.level === 'LOW' ? 'இணைப்புகளைத் திறக்கும் முன் அதிகாரப்பூர்வ எண்ணில் சரிபார்க்கவும்.' : 'எந்த இணைப்பையும் திறக்காதீர்கள், அந்த எண்ணை அழைக்காதீர்கள், OTP அல்லது PIN யாருக்கும் சொல்லாதீர்கள். பணம் இழந்தால் உடனே 1930 ஐ அழைக்கவும்.');
    if (lang === 'hi-IN') return head + (r.level === 'LOW' ? 'लिंक खोलने से पहले आधिकारिक नंबर पर पुष्टि करें।' : 'कोई लिंक न खोलें, उस नंबर पर कॉल न करें और OTP या PIN किसी को न बताएं। पैसे गए हों तो तुरंत 1930 पर कॉल करें।');
    return head + (r.flags.length ? 'The warning signs: ' + r.flags.slice(0, 3).map((f) => f.title.toLowerCase()).join('; ') + '. ' : '') +
      (r.level === 'LOW' ? 'Before opening any link, check with the company’s official app or number.' : 'Do not open the link, do not call the number, and never share an OTP or PIN. If you already paid, call 1930 right away.');
  }
  function streamExplain() {
    clearInterval(S.timer); const full = explainFor(S.report, S.lang); const words = full.split(' '); let i = 0; S.explain = '';
    S.timer = setInterval(() => { i += 2; S.explain = words.slice(0, i).join(' '); const el = document.getElementById('sc-explain'); if (el) el.textContent = S.explain; if (i >= words.length) { clearInterval(S.timer); S.timer = null; if (LA.cur().key === 'scam') LA.refresh(); } }, 90);
  }
  const LEVEL = { HIGH: ['bad', 'Likely a scam', 'Don’t click, call, pay or share anything.'], MEDIUM: ['warn', 'Suspicious', 'Be careful. Check with the real company first.'], LOW: ['ok', 'No common scam signs', 'Still be careful with links and money requests.'] };
  const langChips = (cur, action) => `<div class="chips">${LA.VOICE_LANGS.slice(0, 6).map(([k, l]) => `<button class="chip sm ${cur === k ? 'on' : ''}" data-action="${action}" data-v="${k}">${esc(l)}</button>`).join('')}</div>`;

  LA.screens.scam = {
    render() {
      const r = S.report;
      return `${C.topbar({ title: 'Scam check', sub: 'Checked on this phone · nothing is sent anywhere', back: 'home' })}
      <div class="scroll"><div class="body">
        <div class="card col"><b>Is this message a scam?</b><span class="small muted">Paste an SMS, WhatsApp message or email, add a screenshot, or share a message to LOCAL AI from any app.</span>
          <textarea class="textarea" id="sc-in" placeholder="Message text" data-input="sc-in">${esc(S.text)}</textarea>
          <div class="row" style="gap:8px"><button class="btn btn-ghost grow" data-action="sc-shot">${I('image', 'icon-sm')}Screenshot</button><button class="btn btn-primary grow" data-action="sc-check">Check</button></div>
          <div class="eyebrow">Try a sample</div><div class="chips">${SAMPLES.map(([t], i) => `<button class="chip sm" data-action="sc-sample" data-i="${i}">${esc(t)}</button>`).join('')}</div></div>
        ${r ? `<div class="verdict-card ${LEVEL[r.level][0]}">${I(r.level === 'LOW' ? 'shield' : 'alert')}<div><b>${LEVEL[r.level][1]}</b><span>${LEVEL[r.level][2]}</span></div></div>
          ${r.flags.length ? `<div class="card"><b>Red flags</b>${r.flags.map((f) => `<div class="flag ${f.weight >= 5 ? 'hi' : ''}"><b>${esc(f.title)}</b><span>${esc(f.why)}</span><code>“${esc(f.evidence)}”</code></div>`).join('')}</div>` : ''}
          <div class="card col"><div class="row between"><b>In your language</b>${S.explain && !S.timer ? C.ib('volume', 'sc-speak', 'Read aloud') : ''}</div>${langChips(S.lang, 'sc-lang')}<p id="sc-explain" style="margin:0;font-size:15px">${esc(S.explain)}</p><span class="xs faint">Simulated explanation. In the app the on-device model writes it in your language; the verdict above comes from the rules.</span></div>
          <div class="card"><b>What to do</b><ul class="advice">${ADVICE.map((a) => `<li>${a}</li>`).join('')}</ul></div>` : ''}
        <p class="xs faint" style="margin:0">The verdict comes from rules that run on the phone (the same rules as the app); the model only explains it. When in doubt, don’t act on the message.</p>
      </div></div>`;
    },
    unmount() { clearInterval(S.timer); S.timer = null; LA.speech.stop(); },
    inputs: { 'sc-in': (el) => { S.text = el.value; } },
    actions: {
      'sc-check': () => {
        S.text = document.getElementById('sc-in').value.trim();
        if (S.text.length < 8) { LA.toast('Paste or type the message first'); return; }
        S.report = LA.scamCheck(S.text); LA.refresh(); streamExplain();
      },
      'sc-sample': (el) => { S.text = SAMPLES[+el.getAttribute('data-i')][1]; S.report = LA.scamCheck(S.text); LA.refresh(); streamExplain(); },
      'sc-lang': (el) => { S.lang = el.getAttribute('data-v'); if (S.report) { LA.refresh(); streamExplain(); } else LA.refresh(); },
      'sc-speak': () => LA.speech.speak(S.explain, 'scam'),
      'sc-shot': () => LA.photoSheet((p) => {
        // Simulated OCR of a screenshot: the sample bill photo reads as the electricity message.
        S.text = p === 'bill' || p === 'receipt' ? SAMPLES[0][1] : SAMPLES[2][1];
        LA.toast('Text read from the screenshot (simulated OCR)'); S.report = LA.scamCheck(S.text); LA.refresh(); streamExplain();
      })
    }
  };

  /* ---------- Read it to me ---------- */
  const DOCS = {
    bill: { text: 'POWER DISTRIBUTION LTD\nConsumer no. 04-118-2291\nBilling period Oct 2026\nUnits used 286 kWh\nDue date 28 Nov 2026\nAmount due 2,184', explain: { '': 'This is your electricity bill for October 2026.\nAmount to pay: 2,184 rupees.\nPay before 28 November 2026.\nYou used 286 units this month.', 'ta-IN': 'இது அக்டோபர் 2026 மின்சார கட்டணம்.\nசெலுத்த வேண்டிய தொகை: 2,184 ரூபாய்.\n28 நவம்பர் 2026க்குள் செலுத்தவும்.\nஇந்த மாதம் 286 யூனிட் பயன்படுத்தினீர்கள்.', 'hi-IN': 'यह अक्टूबर 2026 का बिजली बिल है।\nभुगतान राशि: 2,184 रुपये।\n28 नवंबर 2026 से पहले भुगतान करें।\nइस महीने 286 यूनिट खर्च हुए।' } },
    receipt: { text: 'FRESH MART 14 NOV 2026\nBasmati rice 5kg 649\nToor dal 1kg 172\nMilk 2L 112\nTomatoes 1kg 46\nTOTAL 979 PAID BY UPI', explain: { '': 'This is a shop receipt from Fresh Mart, 14 November 2026.\nTotal paid: 979 rupees by UPI.\nThe biggest item was rice for 649.', 'ta-IN': 'இது ஃபிரெஷ் மார்ட் கடை ரசீது, 14 நவம்பர் 2026.\nமொத்தம் 979 ரூபாய் UPI மூலம் செலுத்தப்பட்டது.', 'hi-IN': 'यह फ्रेश मार्ट की रसीद है, 14 नवंबर 2026।\nकुल 979 रुपये UPI से दिए गए।' } },
    plant: { text: '', explain: {} }
  };
  const Rd = { photo: null, text: '', explain: '', lang: '', large: true, busy: false, timer: null };
  function rdExplain() {
    const d = DOCS[Rd.photo]; const full = (d && (d.explain[Rd.lang] || d.explain[''])) || 'This looks like a printed document. (Simulated: the app explains the real text in your language.)';
    clearInterval(Rd.timer); Rd.busy = true; Rd.explain = ''; const words = full.split(' '); let i = 0;
    Rd.timer = setInterval(() => { i += 2; Rd.explain = words.slice(0, i).join(' '); const el = document.getElementById('rd-explain'); if (el) el.textContent = Rd.explain; if (i >= words.length) { clearInterval(Rd.timer); Rd.timer = null; Rd.busy = false; if (LA.cur().key === 'read') LA.refresh(); LA.speech.speak(full, 'rd-explain'); } }, 90);
  }
  LA.screens.read = {
    render() {
      return `${C.topbar({ title: 'Read it to me', sub: 'Letters, bills, forms, medicine labels', back: 'home' })}
      <div class="scroll"><div class="body">
        ${!Rd.photo ? `<div class="card col"><b>Point the camera at the page</b><span class="small muted">The text is read on the phone, read out loud and explained simply in your language.</span><button class="btn btn-primary" data-action="rd-photo">${I('camera', 'icon-sm')}Take or choose a photo</button></div>` : ''}
        ${Rd.photo ? `<img class="rd-photo" src="${LA.photoSrc(Rd.photo)}" alt="Photo">` : ''}
        ${Rd.photo && !Rd.text ? `<div class="banner warn">${I('alert')}<span>No readable text found. Try a sharper photo, taken straight on, in good light.</span></div><button class="btn btn-ghost" data-action="rd-photo">New photo</button>` : ''}
        ${Rd.text ? `<div class="card col"><b>Explanation language</b>${langChips(Rd.lang, 'rd-lang')}<div class="row" style="gap:8px"><button class="btn btn-primary grow" data-action="rd-explain" ${Rd.busy ? 'disabled' : ''}>${Rd.explain ? 'Explain again' : 'Explain simply'}</button><button class="btn btn-tonal grow" data-action="rd-raw">${I('volume', 'icon-sm')}Read as written</button></div></div>
          ${Rd.explain ? `<div class="card"><div class="row between"><b>What it says</b>${C.ib('volume', 'rd-speak', 'Read aloud')}</div><p id="rd-explain" class="${Rd.large ? 'big-read' : ''}" style="white-space:pre-line;margin:6px 0 0">${esc(Rd.explain)}</p></div>` : ''}
          <div class="card"><div class="row between"><b>Text found</b><button class="chip sm ${Rd.large ? 'on' : ''}" data-action="rd-large">A+ Large text</button></div><pre class="${Rd.large ? 'big-read' : ''}" style="white-space:pre-wrap;font-family:var(--font-body);margin:8px 0 0">${esc(Rd.text)}</pre><span class="xs faint">Simulated OCR. In the app the text is read on the phone (Tesseract) in your chosen languages.</span></div>
          <button class="btn btn-ghost" data-action="rd-photo">${I('camera', 'icon-sm')}New photo</button>` : ''}
      </div></div>`;
    },
    unmount() { clearInterval(Rd.timer); Rd.timer = null; Rd.busy = false; LA.speech.stop(); },
    actions: {
      'rd-photo': () => LA.photoSheet((p) => { Rd.photo = p; Rd.explain = ''; Rd.text = DOCS[p] ? DOCS[p].text : 'Text from your photo would appear here.'; LA.refresh(); if (Rd.text) rdExplain(); }),
      'rd-explain': () => { LA.refresh(); rdExplain(); },
      'rd-lang': (el) => { Rd.lang = el.getAttribute('data-v'); LA.refresh(); if (Rd.text) rdExplain(); },
      'rd-raw': () => LA.speech.speak(Rd.text, 'rd-raw'),
      'rd-speak': () => LA.speech.speak(Rd.explain, 'rd-explain'),
      'rd-large': () => { Rd.large = !Rd.large; LA.refresh(); }
    }
  };
})();
